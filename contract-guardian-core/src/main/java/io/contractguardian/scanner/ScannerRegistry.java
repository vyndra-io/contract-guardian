package io.contractguardian.scanner;

import io.contractguardian.policy.SourceConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Registry of available {@link ContractScanner} implementations.
 *
 * <p>Discovers scanners via {@link ServiceLoader} or accepts an explicit list
 * for testing purposes.
 */
public class ScannerRegistry {

    private final List<ContractScanner> scanners;

    /**
     * Creates a registry by discovering scanners via {@link ServiceLoader}.
     *
     * <p>Uses the classloader that loaded {@link ContractScanner} rather than the thread
     * context classloader. This ensures scanner implementations are found in environments
     * with classloader isolation, such as Maven plugins, where the thread context classloader
     * is the host container's classloader rather than the plugin's own classloader.
     */
    public ScannerRegistry() {
        this.scanners = ServiceLoader.load(ContractScanner.class, ContractScanner.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /**
     * Creates a registry with an explicit list of scanners.
     *
     * @param scanners the scanners to register
     */
    public ScannerRegistry(final List<ContractScanner> scanners) {
        this.scanners = List.copyOf(scanners);
    }

    /**
     * Finds the first scanner that can handle the given file.
     *
     * @param file   the file path to check
     * @param config the source configuration for context
     * @return the matching scanner, or empty if none can handle the file
     */
    public Optional<ContractScanner> findScanner(final Path file, final SourceConfig config) {
        return scanners.stream()
                .filter(s -> s.canScan(file, config))
                .findFirst();
    }

    /**
     * Returns all registered scanners.
     *
     * @return an unmodifiable list of scanners
     */
    public List<ContractScanner> allScanners() {
        return Collections.unmodifiableList(scanners);
    }
}
