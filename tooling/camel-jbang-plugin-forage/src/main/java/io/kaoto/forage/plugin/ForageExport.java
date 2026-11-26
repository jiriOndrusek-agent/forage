package io.kaoto.forage.plugin;

import org.aesh.command.option.Mixin;
import org.apache.camel.dsl.jbang.core.commands.Export;

/**
 * Forage export command with property validation.
 *
 * <p>Extends the standard Camel JBang export command to validate Forage properties
 * before exporting the project.
 *
 * @since 1.1
 */
public class ForageExport extends Export {

    @Mixin
    private PropertyValidationMixin validation = new PropertyValidationMixin();

    @Override
    public Integer doCall() throws Exception {
        ForagePlugin.resolveConfigDir(files);

        // Validate properties before exporting
        int validationResult = validation.validateAndReport(printer());
        if (validationResult != 0) {
            return validationResult;
        }

        // Exporting may delegate to run infrastructure that triggers the plugin's beforeRun hook —
        // mark validation as handled so it is not repeated. The finally block resets the flag in
        // case the hook never ran, so a reused plugin instance starts clean.
        ForagePropertyValidator.markValidationHandled();
        try {
            return super.doCall();
        } finally {
            ForagePropertyValidator.consumeValidationHandled();
        }
    }
}
