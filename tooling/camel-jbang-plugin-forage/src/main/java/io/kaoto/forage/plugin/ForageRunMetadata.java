package io.kaoto.forage.plugin;

import java.lang.reflect.Field;
import org.aesh.command.Command;
import org.aesh.command.impl.internal.OptionType;
import org.aesh.command.impl.internal.ProcessedCommand;
import org.aesh.command.impl.internal.ProcessedOptionBuilder;
import org.aesh.command.metadata.CommandMetadataProvider;
import org.aesh.command.metadata.MetadataProviderRegistry;
import org.aesh.command.metadata.MetadataRegistry;
import org.aesh.command.option.Option;
import org.aesh.command.parser.CommandLineParserException;
import org.apache.camel.dsl.jbang.core.commands.Run;

/**
 * The aesh metadata of {@link ForageRun}: the metadata Camel generates for {@link Run}, plus the options of
 * {@link PropertyValidationMixin}.
 *
 * <p>aesh looks up the metadata of a command class, then of its super classes. Without this, {@code ForageRun} gets
 * the metadata of {@code Run}, which lacks {@code --strict} and {@code --skip-validation}, and Camel refuses the
 * command. When {@code Run} has no generated metadata, nothing is registered and aesh reads the annotations.
 */
final class ForageRunMetadata implements CommandMetadataProvider<ForageRun> {

    static final MetadataRegistry REGISTRY = ForageRunMetadata::lookup;

    private final CommandMetadataProvider<Run> run;

    private ForageRunMetadata(CommandMetadataProvider<Run> run) {
        this.run = run;
    }

    private static CommandMetadataProvider<?> lookup(String className) {
        if (!ForageRun.class.getName().equals(className)) {
            return null;
        }
        CommandMetadataProvider<Run> run = MetadataProviderRegistry.getProvider(Run.class);
        return run != null && run.commandType() == Run.class ? new ForageRunMetadata(run) : null;
    }

    @Override
    public Class<ForageRun> commandType() {
        return ForageRun.class;
    }

    @Override
    public ForageRun newInstance() {
        return new ForageRun();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProcessedCommand buildProcessedCommand(ForageRun instance) throws CommandLineParserException {
        return addValidationOptions(run.buildProcessedCommand(instance));
    }

    @Override
    public ProcessedCommand buildHelpProcessedCommand() throws CommandLineParserException {
        return addValidationOptions(run.buildHelpProcessedCommand());
    }

    @Override
    public boolean isGroupCommand() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Command>[] groupCommandClasses() {
        return new Class[0];
    }

    @Override
    public String commandName() {
        return run.commandName();
    }

    /** Adds the boolean options of the mixin, read from its annotations, as aesh would. */
    @SuppressWarnings("unchecked")
    private static ProcessedCommand addValidationOptions(ProcessedCommand command) throws CommandLineParserException {
        for (Field field : PropertyValidationMixin.class.getDeclaredFields()) {
            Option option = field.getAnnotation(Option.class);
            if (option != null) {
                command.addOption(ProcessedOptionBuilder.builder()
                        .name(option.name())
                        .description(option.description())
                        .type(field.getType())
                        .fieldName(field.getName())
                        .mixinFieldName("validation")
                        .hasValue(option.hasValue())
                        .optionType(OptionType.BOOLEAN)
                        .addAllDefaultValues(option.defaultValue())
                        .build());
            }
        }
        return command;
    }
}
