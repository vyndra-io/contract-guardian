package io.contractguardian.db.jpa;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.policy.DbRuleConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks Java source files for breaking database schema changes using JavaParser.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>{@link #check} — for JPA entities ({@code @Entity}): compares {@code @Column}
 *       and {@code @JdbcTypeCode} annotations to detect column removals, constraint
 *       additions, renames, and Map-typed JSONB fields.</li>
 *   <li>{@link #checkJsonbValueClass} — for plain JSONB value classes: compares fields
 *       by name and type to detect removals, incompatible type changes, and Map-typed
 *       fields whose schema cannot be statically enforced.</li>
 * </ul>
 */
class JpaCompatibilityChecker {

    /**
     * Compares the current entity file against its baseline for breaking changes.
     *
     * @param current  the current entity source file
     * @param baseline the baseline entity source file
     * @param config   the rule config controlling which changes are breaking or warnings
     * @param filePath the source file path for reporting
     * @return a list of findings, empty if no issues detected
     */
    List<Finding> check(final Path current, final Path baseline, final DbRuleConfig config,
                        final String filePath) {
        try {
            final CompilationUnit currentCu = StaticJavaParser.parse(current.toFile());
            final CompilationUnit baselineCu = StaticJavaParser.parse(baseline.toFile());

            final List<Finding> findings = new ArrayList<>();

            for (final ClassOrInterfaceDeclaration currentClass : currentCu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!isEntity(currentClass)) {
                    continue;
                }

                final String entityName = currentClass.getNameAsString();
                final Optional<ClassOrInterfaceDeclaration> baselineClass =
                        findEntityByName(baselineCu, entityName);

                if (baselineClass.isEmpty()) {
                    // New entity — no baseline to compare
                    continue;
                }

                final Map<String, JpaFieldMetadata> currentFields = extractFields(currentClass);
                final Map<String, JpaFieldMetadata> baselineFields = extractFields(baselineClass.get());

                findings.addAll(compareFields(currentFields, baselineFields, entityName, config, filePath));
            }

            return findings;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read entity file: " + current, e);
        } catch (Exception e) {
            return List.of(Finding.info(ContractType.DB_JPA_ENTITY, filePath,
                    "parse-skipped",
                    "Java source could not be parsed — some checks may be skipped: " + e.getMessage()));
        }
    }

    private List<Finding> compareFields(final Map<String, JpaFieldMetadata> currentFields,
                                        final Map<String, JpaFieldMetadata> baselineFields,
                                        final String entityName, final DbRuleConfig config,
                                        final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        for (final Map.Entry<String, JpaFieldMetadata> entry : baselineFields.entrySet()) {
            final String fieldName = entry.getKey();
            final JpaFieldMetadata baselineField = entry.getValue();

            if (!currentFields.containsKey(fieldName)) {
                findings.addAll(findingsForRemovedField(baselineField, entityName, config, filePath));
                continue;
            }

            final JpaFieldMetadata currentField = currentFields.get(fieldName);
            findings.addAll(findingsForChangedField(currentField, baselineField, entityName, config, filePath));
        }

        return findings;
    }

    private List<Finding> findingsForRemovedField(final JpaFieldMetadata baseline,
                                                  final String entityName,
                                                  final DbRuleConfig config,
                                                  final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        if (baseline.isJsonb() && config.isBreaking("jsonb-field-removed")) {
            findings.add(JpaFindingFactory.jsonbFieldRemoved(filePath, entityName, baseline.fieldName()));
        } else if (baseline.hasColumn() && config.isBreaking("column-removed")) {
            findings.add(JpaFindingFactory.columnRemoved(
                    filePath, entityName, baseline.fieldName(), baseline.columnName()));
        }

        return findings;
    }

    private List<Finding> findingsForChangedField(final JpaFieldMetadata current,
                                                  final JpaFieldMetadata baseline,
                                                  final String entityName,
                                                  final DbRuleConfig config,
                                                  final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        if (baseline.nullable() && !current.nullable() && config.isBreaking("not-null-added-no-default")) {
            findings.add(JpaFindingFactory.notNullAddedNoDefault(
                    filePath, entityName, current.fieldName(), current.columnName()));
        }

        if (!baseline.columnName().equals(current.columnName()) && config.isWarning("column-renamed")) {
            findings.add(JpaFindingFactory.columnRenamed(
                    filePath, entityName, current.fieldName(), baseline.columnName(), current.columnName()));
        }

        if (baseline.isJsonb() && isMapType(baseline.fieldType())
                && config.isWarning("jsonb-schema-unenforceable")) {
            findings.add(JpaFindingFactory.jsonbSchemaUnenforceable(
                    filePath, entityName, baseline.fieldName()));
        }

        return findings;
    }

    /**
     * Checks a non-entity JSONB value class for breaking field changes.
     *
     * <p>Detects fields removed from the class, fields whose type changed incompatibly,
     * and fields typed as {@code Map<>} whose schema cannot be statically enforced.
     *
     * @param current  the current version of the value class source file
     * @param baseline the baseline version of the value class source file
     * @param config   the rule config
     * @param filePath the source file path for reporting
     * @return a list of findings, empty if no issues detected
     */
    List<Finding> checkJsonbValueClass(final Path current, final Path baseline,
                                       final DbRuleConfig config, final String filePath) {
        try {
            final CompilationUnit currentCu = StaticJavaParser.parse(current.toFile());
            final CompilationUnit baselineCu = StaticJavaParser.parse(baseline.toFile());
            final List<Finding> findings = new ArrayList<>();

            for (final ClassOrInterfaceDeclaration currentClass
                    : currentCu.findAll(ClassOrInterfaceDeclaration.class)) {
                final String className = currentClass.getNameAsString();
                final Optional<ClassOrInterfaceDeclaration> baselineClass =
                        baselineCu.findAll(ClassOrInterfaceDeclaration.class).stream()
                                .filter(c -> className.equals(c.getNameAsString()))
                                .findFirst();

                if (baselineClass.isEmpty()) {
                    continue;
                }

                final Map<String, JpaFieldMetadata> currentFields = extractFields(currentClass);
                final Map<String, JpaFieldMetadata> baselineFields = extractFields(baselineClass.get());
                findings.addAll(compareJsonbValueClassFields(
                        currentFields, baselineFields, className, config, filePath));
            }

            return findings;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JSONB value class: " + current, e);
        } catch (Exception e) {
            return List.of(Finding.info(ContractType.DB_JSONB, filePath,
                    "parse-skipped",
                    "Java source could not be parsed — some checks may be skipped: " + e.getMessage()));
        }
    }

    private List<Finding> compareJsonbValueClassFields(final Map<String, JpaFieldMetadata> currentFields,
                                                       final Map<String, JpaFieldMetadata> baselineFields,
                                                       final String className,
                                                       final DbRuleConfig config,
                                                       final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        for (final Map.Entry<String, JpaFieldMetadata> entry : baselineFields.entrySet()) {
            final String fieldName = entry.getKey();
            final JpaFieldMetadata baselineField = entry.getValue();

            if (isMapType(baselineField.fieldType())) {
                if (config.isWarning("jsonb-schema-unenforceable")) {
                    findings.add(JpaFindingFactory.jsonbSchemaUnenforceable(filePath, className, fieldName));
                }
                continue;
            }

            if (!currentFields.containsKey(fieldName)) {
                if (config.isBreaking("jsonb-field-removed")) {
                    findings.add(JpaFindingFactory.jsonbFieldRemoved(filePath, className, fieldName));
                }
                continue;
            }

            final JpaFieldMetadata currentField = currentFields.get(fieldName);
            if (!baselineField.fieldType().equals(currentField.fieldType())
                    && config.isBreaking("jsonb-field-type-changed")) {
                findings.add(JpaFindingFactory.jsonbValueFieldTypeChanged(
                        filePath, className, fieldName,
                        baselineField.fieldType(), currentField.fieldType()));
            }
        }

        return findings;
    }

    private boolean isMapType(final String fieldType) {
        return fieldType.startsWith("Map<") || fieldType.equals("Map")
                || fieldType.startsWith("java.util.Map");
    }

    private Map<String, JpaFieldMetadata> extractFields(final ClassOrInterfaceDeclaration cls) {
        final Map<String, JpaFieldMetadata> fields = new LinkedHashMap<>();

        for (final FieldDeclaration field : cls.getFields()) {
            final String fieldName = field.getVariables().get(0).getNameAsString();
            final String fieldType = field.getVariables().get(0).getType().asString();

            final boolean hasColumn = field.getAnnotationByName("Column").isPresent();
            final boolean isJsonb = isJsonbField(field);

            final String columnName = resolveColumnName(field, fieldName);
            final boolean nullable = resolveNullable(field);

            fields.put(fieldName, new JpaFieldMetadata(fieldName, columnName, nullable, hasColumn, isJsonb, fieldType));
        }

        return fields;
    }

    private boolean isJsonbField(final FieldDeclaration field) {
        if (field.getAnnotationByName("JdbcTypeCode").isPresent()) {
            return true;
        }
        return field.getAnnotationByName("Column")
                .filter(a -> a.toString().contains("jsonb"))
                .isPresent();
    }

    private String resolveColumnName(final FieldDeclaration field, final String fieldName) {
        final Optional<AnnotationExpr> columnAnnotation = field.getAnnotationByName("Column");
        if (columnAnnotation.isPresent() && columnAnnotation.get() instanceof NormalAnnotationExpr normal) {
            for (final MemberValuePair pair : normal.getPairs()) {
                if ("name".equals(pair.getNameAsString())) {
                    return pair.getValue().asStringLiteralExpr().asString();
                }
            }
        }
        return toSnakeCase(fieldName);
    }

    private boolean resolveNullable(final FieldDeclaration field) {
        final Optional<AnnotationExpr> columnAnnotation = field.getAnnotationByName("Column");
        if (columnAnnotation.isPresent() && columnAnnotation.get() instanceof NormalAnnotationExpr normal) {
            for (final MemberValuePair pair : normal.getPairs()) {
                if ("nullable".equals(pair.getNameAsString())) {
                    return Boolean.parseBoolean(pair.getValue().toString());
                }
            }
        }
        return true;
    }

    private boolean isEntity(final ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("Entity").isPresent();
    }

    private Optional<ClassOrInterfaceDeclaration> findEntityByName(final CompilationUnit cu,
                                                                    final String name) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(cls -> name.equals(cls.getNameAsString()) && isEntity(cls))
                .findFirst();
    }

    /**
     * Converts a camelCase field name to its snake_case column name equivalent.
     *
     * <p>This mirrors JPA's default column naming strategy.
     *
     * @param fieldName the Java field name
     * @return the snake_case column name
     */
    private String toSnakeCase(final String fieldName) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            final char c = fieldName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}
