package io.kaoto.forage.plugin.config;

import org.aesh.command.CommandDefinition;
import org.apache.camel.dsl.jbang.core.commands.CamelCommand;

@CommandDefinition(name = "config", description = "Forage configuration management commands", generateHelp = true)
public class ConfigCommand extends CamelCommand {

    @Override
    public Integer doCall() {
        return printHelp();
    }
}
