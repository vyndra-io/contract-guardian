package io.contractguardian.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for Contract Guardian.
 *
 * <p>Provides subcommands for scanning, initializing config, and validating config files.
 */
@Command(name = "contract-guardian", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Unified schema governance for microservices",
        subcommands = {
                ScanCommand.class,
                InitCommand.class,
                ValidateCommand.class
        })
public class ContractGuardianCli implements Runnable {

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        final int exitCode = new CommandLine(new ContractGuardianCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
