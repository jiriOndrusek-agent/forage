package io.kaoto.forage.plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.dsl.jbang.core.commands.CamelCommand;
import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.CommandTree;
import org.apache.camel.dsl.jbang.run.common.RuntimeType;
import org.apache.camel.dsl.jbang.run.common.StringPrinter;
import io.kaoto.forage.plugin.config.ConfigReadCommand;
import io.kaoto.forage.plugin.config.ConfigWriteCommand;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Forage commands on the aesh command line of Camel JBang: names, options and help.
 */
class ForagePluginCommandsTest {

    private final StringPrinter printer = new StringPrinter();
    private final List<CamelCommand> parsed = new ArrayList<>();

    @BeforeAll
    static void generatedMetadata() {
        // as the CLI does: Run then has its generated aesh metadata
        CamelJBangMain.useCommandMetadata();
    }

    private int run(String... args) {
        CamelJBangMain main = new CamelJBangMain() {
            @Override
            public boolean parseOnly(CamelCommand command) {
                parsed.add(command);
                return true;
            }
        }.withPrinter(printer);
        CommandTree root = new CommandTree(main).setOutput(printer::print);
        new ForagePlugin().customize(root, main);
        return main.run(root, args);
    }

    /** The value of a field of the object or of its super classes. */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // look in the super class
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private <T> T parsed(Class<T> type) {
        assertThat(parsed).hasSize(1).first().isInstanceOf(type);
        return (T) parsed.get(0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "forage",
                "forage config",
                "forage config read",
                "forage config write",
                "forage run",
                "forage export"
            })
    void helpOfEveryCommand(String command) {
        List<String> args = new ArrayList<>(List.of(command.split(" ")));
        args.add("--help");
        assertThat(run(args.toArray(String[]::new))).isZero();
        assertThat(printer.getOutput()).contains("Usage:");
    }

    @Test
    void forageListsItsSubcommands() {
        run("forage", "--help");
        assertThat(printer.getOutput()).contains("config", "run", "export");
    }

    @Test
    void runHasTheOptionsOfRunAndOfForage() throws Exception {
        run("forage", "run", "--help");
        assertThat(printer.getOutput()).contains("--strict", "--skip-validation", "--background", "--dev");

        assertThat(run("forage", "run", "--strict", "--skip-validation", "--background", "app.yaml"))
                .isZero();
        ForageRun command = parsed(ForageRun.class);
        assertThat(command.files).containsExactly("app.yaml");
        assertThat(command.background).isTrue();
        assertThat(field(command.validation, "strict")).isEqualTo(true);
        assertThat(field(command.validation, "skipValidation")).isEqualTo(true);
    }

    @Test
    void runValidationDefaultsToOff() throws Exception {
        assertThat(run("forage", "run", "app.yaml")).isZero();
        ForageRun command = parsed(ForageRun.class);
        assertThat(field(command.validation, "strict")).isEqualTo(false);
        assertThat(field(command.validation, "skipValidation")).isEqualTo(false);
    }

    @Test
    void exportHasTheOptionsOfExportAndOfForage() throws Exception {
        run("forage", "export", "--help");
        assertThat(printer.getOutput()).contains("--strict", "--skip-validation", "--runtime");

        assertThat(run("forage", "export", "--strict", "--runtime=quarkus", "app.yaml"))
                .isZero();
        ForageExport command = parsed(ForageExport.class);
        assertThat(field(command, "runtime")).isEqualTo(RuntimeType.quarkus);
        assertThat(field(field(command, "validation"), "strict")).isEqualTo(true);
        assertThat(field(field(command, "validation"), "skipValidation")).isEqualTo(false);
    }

    @Test
    void configReadOptions() throws Exception {
        assertThat(run("forage", "config", "read", "-d", "dir", "-f", "jdbc", "-s", "forage"))
                .isZero();
        ConfigReadCommand command = parsed(ConfigReadCommand.class);
        assertThat(((File) field(command, "directory")).getName()).isEqualTo("dir");
        assertThat(field(command, "filter")).isEqualTo("jdbc");
        assertThat(field(field(command, "strategyOption"), "strategy")).isEqualTo("forage");
    }

    @Test
    void configWriteOptions() throws Exception {
        assertThat(run("forage", "config", "write", "--input", "{}", "--dir", "dir", "--delete", "--name", "ds1"))
                .isZero();
        ConfigWriteCommand command = parsed(ConfigWriteCommand.class);
        assertThat(field(command, "input")).isEqualTo("{}");
        assertThat(((File) field(command, "directory")).getName()).isEqualTo("dir");
        assertThat(field(command, "delete")).isEqualTo(true);
        assertThat(field(command, "instanceName")).isEqualTo("ds1");
        assertThat(field(field(command, "strategyOption"), "strategy")).isEqualTo("application");
    }
}
