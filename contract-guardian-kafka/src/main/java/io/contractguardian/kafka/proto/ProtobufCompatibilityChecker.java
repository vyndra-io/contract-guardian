package io.contractguardian.kafka.proto;

import com.squareup.wire.schema.internal.parser.EnumConstantElement;
import com.squareup.wire.schema.internal.parser.EnumElement;
import com.squareup.wire.schema.internal.parser.FieldElement;
import com.squareup.wire.schema.internal.parser.MessageElement;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ReservedElement;
import com.squareup.wire.schema.internal.parser.TypeElement;
import io.contractguardian.model.Finding;
import io.contractguardian.policy.KafkaRuleConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks compatibility between two Protobuf schema versions.
 *
 * <p>Detects the following breaking changes:
 * <ul>
 *   <li>Field removed without a {@code reserved} declaration</li>
 *   <li>Field type changed for the same tag number</li>
 *   <li>Tag number reused for a different field</li>
 *   <li>Enum value removed</li>
 * </ul>
 *
 * <p>Note: Only {@link KafkaRuleConfig.CompatibilityMode#BACKWARD},
 * {@link KafkaRuleConfig.CompatibilityMode#FULL}, and
 * {@link KafkaRuleConfig.CompatibilityMode#NONE} are meaningful for Protobuf.
 * Forward-only mode applies the same checks as backward.
 */
public class ProtobufCompatibilityChecker {

    private final ProtobufFindingFactory findingFactory = new ProtobufFindingFactory();

    /**
     * Checks compatibility between the current and baseline Protobuf schemas.
     *
     * @param current  the new schema version
     * @param baseline the previous schema version
     * @param mode     the compatibility mode to enforce
     * @param filePath the file path for reporting purposes
     * @return a list of findings, empty if compatible
     */
    public List<Finding> check(final ProtoFileElement current, final ProtoFileElement baseline,
                               final KafkaRuleConfig.CompatibilityMode mode,
                               final String filePath) {
        if (mode == KafkaRuleConfig.CompatibilityMode.NONE) {
            return List.of();
        }

        final List<Finding> findings = new ArrayList<>();

        // Build lookup maps for current types
        final Map<String, MessageElement> currentMessages = indexMessages(current);
        final Map<String, EnumElement> currentEnums = indexEnums(current);

        // Check each baseline message against the current version
        for (final TypeElement type : baseline.getTypes()) {
            if (type instanceof MessageElement baselineMsg) {
                final MessageElement currentMsg = currentMessages.get(baselineMsg.getName());
                if (currentMsg != null) {
                    findings.addAll(checkMessage(baselineMsg, currentMsg, filePath));
                }
            } else if (type instanceof EnumElement baselineEnum) {
                final EnumElement currentEnum = currentEnums.get(baselineEnum.getName());
                if (currentEnum != null) {
                    findings.addAll(checkEnum(baselineEnum, currentEnum, filePath));
                }
            }
        }

        return findings;
    }

    private List<Finding> checkMessage(final MessageElement baseline, final MessageElement current,
                                       final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        final String messageName = baseline.getName();

        // Build tag-to-field maps
        final Map<Integer, FieldElement> currentByTag = indexFieldsByTag(current.getFields());
        final Map<String, FieldElement> currentByName = indexFieldsByName(current.getFields());
        final Set<Integer> reservedTags = collectReservedTags(current.getReserveds());
        final Set<String> reservedNames = collectReservedNames(current.getReserveds());

        // Check each baseline field
        for (final FieldElement baselineField : baseline.getFields()) {
            final int tag = baselineField.getTag();
            final String name = baselineField.getName();
            final String type = baselineField.getType();

            final FieldElement currentFieldByTag = currentByTag.get(tag);
            final FieldElement currentFieldByName = currentByName.get(name);

            if (currentFieldByTag == null && !reservedTags.contains(tag)) {
                // Field removed without reserved tag
                if (!reservedNames.contains(name)) {
                    findings.add(findingFactory.fieldRemovedWithoutReserved(name, messageName, tag, filePath));
                }
            } else if (currentFieldByTag != null) {
                // Same tag exists — check for type change
                if (!type.equals(currentFieldByTag.getType())) {
                    findings.add(findingFactory.fieldTypeChanged(
                            name, messageName, type, currentFieldByTag.getType(), filePath));
                }
                // Check for tag reuse (same tag, different name)
                if (!name.equals(currentFieldByTag.getName()) && currentFieldByName == null) {
                    findings.add(findingFactory.tagReused(
                            tag, messageName, name, currentFieldByTag.getName(), filePath));
                }
            }
        }

        return findings;
    }

    private List<Finding> checkEnum(final EnumElement baseline, final EnumElement current,
                                    final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        final String enumName = baseline.getName();

        final Set<String> currentConstantNames = new HashSet<>();
        for (final EnumConstantElement c : current.getConstants()) {
            currentConstantNames.add(c.getName());
        }
        final Set<Integer> reservedNumbers = collectReservedTags(current.getReserveds());

        for (final EnumConstantElement baselineConst : baseline.getConstants()) {
            if (!currentConstantNames.contains(baselineConst.getName())
                    && !reservedNumbers.contains(baselineConst.getTag())) {
                findings.add(findingFactory.enumValueRemoved(
                        baselineConst.getName(), enumName, baselineConst.getTag(), filePath));
            }
        }

        return findings;
    }

    private Map<String, MessageElement> indexMessages(final ProtoFileElement protoFile) {
        final Map<String, MessageElement> index = new HashMap<>();
        for (final TypeElement type : protoFile.getTypes()) {
            if (type instanceof MessageElement msg) {
                index.put(msg.getName(), msg);
            }
        }
        return index;
    }

    private Map<String, EnumElement> indexEnums(final ProtoFileElement protoFile) {
        final Map<String, EnumElement> index = new HashMap<>();
        for (final TypeElement type : protoFile.getTypes()) {
            if (type instanceof EnumElement enumType) {
                index.put(enumType.getName(), enumType);
            }
        }
        return index;
    }

    private Map<Integer, FieldElement> indexFieldsByTag(final List<FieldElement> fields) {
        final Map<Integer, FieldElement> index = new HashMap<>();
        for (final FieldElement field : fields) {
            index.put(field.getTag(), field);
        }
        return index;
    }

    private Map<String, FieldElement> indexFieldsByName(final List<FieldElement> fields) {
        final Map<String, FieldElement> index = new HashMap<>();
        for (final FieldElement field : fields) {
            index.put(field.getName(), field);
        }
        return index;
    }

    private Set<Integer> collectReservedTags(final List<ReservedElement> reserveds) {
        final Set<Integer> tags = new HashSet<>();
        for (final ReservedElement reserved : reserveds) {
            for (final Object value : reserved.getValues()) {
                if (value instanceof Integer intVal) {
                    tags.add(intVal);
                } else if (value instanceof com.squareup.wire.schema.internal.parser.ExtensionsElement) {
                    // ranges — not common in reserved, skip for now
                }
            }
        }
        return tags;
    }

    private Set<String> collectReservedNames(final List<ReservedElement> reserveds) {
        final Set<String> names = new HashSet<>();
        for (final ReservedElement reserved : reserveds) {
            for (final Object value : reserved.getValues()) {
                if (value instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
