package io.kaoto.forage.plugin;

import org.aesh.command.CommandDefinition;
import org.apache.camel.dsl.jbang.core.commands.CamelCommand;

@CommandDefinition(
        name = "forage",
        description = "Forage commands (use --help to see sub commands)",
        generateHelp = true)
public class ForageCommand extends CamelCommand {

    @Override
    public Integer doCall() {
        //        new CommandLine(this).execute("--help");
        printer().println("Hello from Forage!");
        return 0;
    }
}
