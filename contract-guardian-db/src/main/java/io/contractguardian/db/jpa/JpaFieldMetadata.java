package io.contractguardian.db.jpa;

/**
 * Metadata extracted from a JPA entity field for compatibility comparison.
 *
 * @param fieldName    the Java field name
 * @param columnName   the mapped database column name (from {@code @Column(name = ...)},
 *                     or the field name if not explicitly set)
 * @param nullable     whether the column allows null values (from {@code @Column(nullable = ...)},
 *                     defaults to {@code true})
 * @param hasColumn    whether the field has a {@code @Column} annotation
 * @param isJsonb      whether the field is mapped as a JSONB column
 * @param fieldType    the simple Java type name of the field
 */
public record JpaFieldMetadata(
        String fieldName,
        String columnName,
        boolean nullable,
        boolean hasColumn,
        boolean isJsonb,
        String fieldType
) {
}
