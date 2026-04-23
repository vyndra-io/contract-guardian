package io.contractguardian.rest;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.policy.RestRuleConfig;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.ChangedParameter;
import org.openapitools.openapidiff.core.model.ChangedSchema;
import org.openapitools.openapidiff.core.model.Endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts openapi-diff change objects into {@link Finding} records.
 *
 * <p>Classifies each detected change as breaking or warning based on the
 * {@link RestRuleConfig} rules, and generates human-readable messages with fix suggestions.
 */
public class OpenApiFindingFactory {

    /**
     * Creates a breaking finding for a removed endpoint.
     *
     * @param endpoint the removed endpoint
     * @param filePath the file path for reporting
     * @return a breaking finding
     */
    public Finding endpointRemoved(final Endpoint endpoint, final String filePath) {
        final String path = endpoint.getPathUrl();
        final String method = endpoint.getMethod().name();
        return Finding.breaking(ContractType.REST_OPENAPI, filePath,
                "endpoint-removed",
                String.format("Endpoint removed: %s %s", method, path),
                null,
                "Restore the endpoint or coordinate removal with all consumers before deleting it");
    }

    /**
     * Creates an info finding for a new endpoint.
     *
     * @param endpoint the new endpoint
     * @param filePath the file path for reporting
     * @return an info finding
     */
    public Finding endpointAdded(final Endpoint endpoint, final String filePath) {
        final String path = endpoint.getPathUrl();
        final String method = endpoint.getMethod().name();
        return Finding.info(ContractType.REST_OPENAPI, filePath,
                "endpoint-added",
                String.format("New endpoint added: %s %s", method, path));
    }

    /**
     * Creates findings for changed parameters on an operation.
     *
     * @param operation the changed operation
     * @param config    the REST rule config for severity classification
     * @param filePath  the file path for reporting
     * @return a list of findings for parameter changes
     */
    public List<Finding> fromParameterChanges(final ChangedOperation operation,
                                              final RestRuleConfig config,
                                              final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        final String path = operation.getPathUrl();
        final String method = operation.getHttpMethod().name();

        if (operation.getParameters() == null) {
            return findings;
        }

        // New required parameters are breaking — consumers won't pass them
        for (final Parameter param : operation.getParameters().getIncreased()) {
            if (Boolean.TRUE.equals(param.getRequired())) {
                final String rule = "required-param-added";
                final String msg = String.format(
                        "Required parameter '%s' added to %s %s — existing callers will break",
                        param.getName(), method, path);
                if (config.isBreaking(rule)) {
                    findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg,
                            null, "Make the parameter optional or provide a default value"));
                } else if (config.isWarning(rule)) {
                    findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
                }
            }
        }

        // Changed parameters — check for required becoming true
        for (final ChangedParameter param : operation.getParameters().getChanged()) {
            if (param.isChangeRequired() && Boolean.TRUE.equals(param.getNewParameter().getRequired())) {
                final String rule = "required-param-added";
                final String msg = String.format(
                        "Parameter '%s' became required on %s %s", param.getName(), method, path);
                if (config.isBreaking(rule)) {
                    findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg,
                            null, "Make the parameter optional with a default value"));
                } else if (config.isWarning(rule)) {
                    findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
                }
            }
        }

        return findings;
    }

    /**
     * Creates findings for changed response schemas on an operation.
     *
     * @param operation the changed operation
     * @param config    the REST rule config for severity classification
     * @param filePath  the file path for reporting
     * @return a list of findings for response schema changes
     */
    public List<Finding> fromResponseChanges(final ChangedOperation operation,
                                             final RestRuleConfig config,
                                             final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        if (operation.getApiResponses() == null) {
            return findings;
        }

        final String path = operation.getPathUrl();
        final String method = operation.getHttpMethod().name();

        // Missing response status codes
        for (final Map.Entry<String, ?> entry : operation.getApiResponses().getMissing().entrySet()) {
            final String rule = "status-code-removed";
            final String msg = String.format(
                    "Response status code %s removed from %s %s", entry.getKey(), method, path);
            if (config.isBreaking(rule)) {
                findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg,
                        null, "Restore the status code or ensure all consumers handle its absence"));
            } else if (config.isWarning(rule)) {
                findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
            }
        }

        // Changed response schemas
        for (final var entry : operation.getApiResponses().getChanged().entrySet()) {
            final String statusCode = entry.getKey();
            final var changedResponse = entry.getValue();
            if (changedResponse.getContent() == null) {
                continue;
            }
            for (final var mediaEntry : changedResponse.getContent().getChanged().entrySet()) {
                final var changedMediaType = mediaEntry.getValue();
                if (changedMediaType.getSchema() != null) {
                    findings.addAll(fromSchemaChanges(
                            changedMediaType.getSchema(), config, filePath, method, path, statusCode));
                }
            }
        }

        return findings;
    }

    private List<Finding> fromSchemaChanges(final ChangedSchema schema,
                                            final RestRuleConfig config,
                                            final String filePath,
                                            final String method,
                                            final String path,
                                            final String statusCode) {
        final List<Finding> findings = new ArrayList<>();

        // Removed response fields
        for (final String removed : schema.getMissingProperties().keySet()) {
            final String rule = "response-field-removed";
            final String msg = String.format(
                    "Response field '%s' removed from %s %s (%s)", removed, method, path, statusCode);
            if (config.isBreaking(rule)) {
                findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg,
                        null, "Keep the field and mark it deprecated before removing in a future version"));
            } else if (config.isWarning(rule)) {
                findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
            }
        }

        // Type changes in existing response fields
        for (final Map.Entry<String, ChangedSchema> entry : schema.getChangedProperties().entrySet()) {
            final ChangedSchema changedProp = entry.getValue();
            if (changedProp.getOldSchema() != null && changedProp.getNewSchema() != null) {
                final String oldType = changedProp.getOldSchema().getType();
                final String newType = changedProp.getNewSchema().getType();
                if (oldType != null && !oldType.equals(newType)) {
                    final String rule = "response-field-type-changed";
                    final String msg = String.format(
                            "Response field '%s' type changed: %s → %s in %s %s (%s)",
                            entry.getKey(), oldType, newType, method, path, statusCode);
                    if (config.isBreaking(rule)) {
                        findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg,
                                null, "Use a union type or introduce a new field to avoid breaking consumers"));
                    } else if (config.isWarning(rule)) {
                        findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
                    }
                }
            }

            // Recursively check nested property changes
            findings.addAll(fromSchemaChanges(
                    changedProp, config, filePath, method, path, statusCode));
        }

        // Deprecated fields
        if (schema.isChangeDeprecated()) {
            for (final String prop : schema.getIncreasedProperties().keySet()) {
                final String rule = "response-field-deprecated";
                final String msg = String.format(
                        "Response field '%s' deprecated in %s %s (%s)", prop, method, path, statusCode);
                if (config.isBreaking(rule)) {
                    findings.add(Finding.breaking(ContractType.REST_OPENAPI, filePath, rule, msg));
                } else if (config.isWarning(rule)) {
                    findings.add(Finding.warning(ContractType.REST_OPENAPI, filePath, rule, msg));
                }
            }
        }

        return findings;
    }
}
