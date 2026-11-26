package io.kaoto.forage.plugin;

import org.aesh.command.option.Mixin;
import org.apache.camel.dsl.jbang.core.commands.Run;

/**
 * Forage run command with property validation.
 *
 * <p>Extends the standard Camel JBang run command to resolve the Forage
 * configuration directory and validate Forage properties before running.
 *
 * <p>Users can run Forage applications via {@code camel forage run app/*}
 * (this subcommand) or via {@code camel run app/*} (the standard Camel run
 * command, where the Forage plugin hooks in via {@link ForagePlugin#getRunCustomizer()}).
 * Both paths use the same configuration directory resolution logic.
 *
 * @since 1.0
 */
public class ForageRun extends Run {

    @Mixin
    PropertyValidationMixin validation = new PropertyValidationMixin();

    @Override
    public Integer doCall() throws Exception {
        ForagePlugin.resolveConfigDir(files);

        // Validate properties before running
        int validationResult = validation.validateAndReport(printer());
        if (validationResult != 0) {
            return validationResult;
        }

        // super.doCall() triggers the plugin's beforeRun hook (ForagePlugin), which would validate
        // again — mark validation as handled so warnings are not printed twice and --skip-validation
        // fully suppresses output. The hook consumes the flag; the finally block resets it in case
        // the hook never ran (e.g. an early failure), so a reused plugin instance starts clean.
        ForagePropertyValidator.markValidationHandled();
        try {
            return super.doCall();
        } finally {
            ForagePropertyValidator.consumeValidationHandled();
        }
    }
}
