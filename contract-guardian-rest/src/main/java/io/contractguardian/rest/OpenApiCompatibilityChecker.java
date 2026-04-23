package io.contractguardian.rest;

import io.contractguardian.model.Finding;
import io.contractguardian.policy.RestRuleConfig;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.Endpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares two OpenAPI specifications using openapi-diff and converts the result into findings.
 *
 * <p>Delegates finding classification and message generation to {@link OpenApiFindingFactory}.
 */
public class OpenApiCompatibilityChecker {

    private final OpenApiFindingFactory findingFactory = new OpenApiFindingFactory();

    /**
     * Compares the current OpenAPI spec content against the baseline and produces findings.
     *
     * @param currentContent  the raw content of the current spec
     * @param baselineContent the raw content of the baseline spec
     * @param config          the REST rule config for severity classification
     * @param filePath        the file path for reporting purposes
     * @return a list of findings, empty if no relevant changes are detected
     * @throws OpenApiScanException if the comparison fails due to parse errors
     */
    public List<Finding> check(final String currentContent, final String baselineContent,
                               final RestRuleConfig config, final String filePath) {
        final ChangedOpenApi diff;
        try {
            diff = OpenApiCompare.fromContents(baselineContent, currentContent);
        } catch (Exception e) {
            throw new OpenApiScanException("Failed to compare OpenAPI specs: " + filePath, e);
        }

        final List<Finding> findings = new ArrayList<>();

        // Removed endpoints are always breaking (unless ignored by path)
        for (final Endpoint removed : diff.getMissingEndpoints()) {
            if (!config.isIgnored(removed.getPathUrl())) {
                findings.add(findingFactory.endpointRemoved(removed, filePath));
            }
        }

        // New endpoints are informational
        for (final Endpoint added : diff.getNewEndpoints()) {
            if (!config.isIgnored(added.getPathUrl())) {
                findings.add(findingFactory.endpointAdded(added, filePath));
            }
        }

        // Changed operations — check parameters and responses
        for (final ChangedOperation operation : diff.getChangedOperations()) {
            if (config.isIgnored(operation.getPathUrl())) {
                continue;
            }
            findings.addAll(findingFactory.fromParameterChanges(operation, config, filePath));
            findings.addAll(findingFactory.fromResponseChanges(operation, config, filePath));
        }

        return findings;
    }
}
