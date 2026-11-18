package io.kaoto.forage.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import io.kaoto.forage.core.common.ForageModuleDescriptor;
import io.kaoto.forage.core.common.QuarkusConfigHook;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.PropertyFileLocator;
import io.kaoto.forage.jdbc.common.DataSourceFactoryConfig;
import io.kaoto.forage.jdbc.common.JdbcModuleDescriptor;
import io.kaoto.forage.jdbc.common.JdbcQuarkusConfigHook;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuarkusConfigExportTest {

    private static final String DS1 =
            """
            forage.ds1.jdbc.db.kind=postgresql
            forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/db
            forage.ds1.jdbc.username=test
            forage.ds1.jdbc.password=secret
            forage.ds1.jdbc.pool.min.size=4
            """;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        System.clearProperty("forage.ds1.jdbc.username");
        System.clearProperty("forage.ds1.jdbc.idempotent.repository.enabled");
        System.clearProperty("forage.ds1.jdbc.properties.ssl");
        ConfigStore.getInstance().reload();
    }

    @Test
    void translatesPlainNamedDataSource() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1));

        assertThat(result.modules()).containsExactly("jdbc");
        assertThat(result.properties())
                .containsEntry("quarkus.datasource.\"ds1\".db-kind", "postgresql")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db")
                .containsEntry("quarkus.datasource.\"ds1\".username", "test")
                .containsEntry("quarkus.datasource.\"ds1\".password", "secret")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
        assertThat(result.forageKeys()).hasSize(5).allMatch(key -> key.startsWith("forage.ds1.jdbc."));
        assertThat(result.messages())
                .singleElement()
                .asString()
                .contains("QUARKUS_DATASOURCE__DS1__JDBC_URL")
                .contains("FORAGE_DS1_JDBC_*");
    }

    @Test
    void ignoresExportTimeOverrides() {
        System.setProperty("forage.ds1.jdbc.username", "leaked");

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1));

        assertThat(result.properties()).containsEntry("quarkus.datasource.\"ds1\".username", "test");
        assertThat(result.messages()).singleElement().asString().contains("Ignored");
    }

    @Test
    void keepsModuleWhenOneDataSourceUsesTransactions() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(
                project(
                        DS1
                                + """
                forage.ds2.jdbc.db.kind=h2
                forage.ds2.jdbc.url=jdbc:h2:mem:x
                forage.ds2.jdbc.username=sa
                forage.ds2.jdbc.password=sa
                forage.ds2.jdbc.transaction.enabled=true
                """));

        assertNotTranslated(result, "'ds2' uses forage.ds2.jdbc.transaction.enabled");
    }

    @Test
    void translatesDriverProperties() {
        QuarkusConfigExport.Result result =
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbc.properties.ssl=true\n"));

        assertThat(result.properties())
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.additional-jdbc-properties.\"ssl\"", "true");
    }

    @Test
    void ignoresExportTimeDriverProperties() {
        System.setProperty("forage.ds1.jdbc.properties.ssl", "true");

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1));

        assertThat(result.properties().keySet()).noneMatch(key -> key.contains("additional-jdbc-properties"));
        assertThat(System.getProperty("forage.ds1.jdbc.properties.ssl")).isEqualTo("true");
    }

    @Test
    void keepsPlaceholders() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbc.password={{env:DB_PASSWORD}}\n")),
                "placeholders");
    }

    @Test
    void keepsProfileSpecificValues() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "%prod.forage.ds1.jdbc.url=jdbc:postgresql://prod/db\n")),
                "profile-specific");
    }

    @Test
    void keepsIncompleteConfiguration() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project("forage.jdbc.url=jdbc:postgresql://localhost/db\n")), "'jdbc'");
    }

    @Test
    void translatesDefaultArtemisConnectionFactory() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(
                project(
                        """
                forage.jms.kind=artemis
                forage.jms.broker.url=tcp://localhost:61616
                forage.jms.username=artemis
                forage.jms.password=artemis
                """));

        assertThat(result.modules()).containsExactly("jms");
        assertThat(result.properties())
                .containsEntry("quarkus.artemis.url", "tcp://localhost:61616")
                .containsEntry("quarkus.artemis.username", "artemis");
        assertThat(result.forageKeys()).hasSize(4);
        assertThat(result.groups()).containsExactly("jms-connection-pool");
    }

    @Test
    void disablesPoolingExplicitlyWhenPoolingDisabled() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(
                project(
                        """
                forage.jms.kind=artemis
                forage.jms.broker.url=tcp://localhost:61616
                forage.jms.pool.enabled=false
                forage.jms.pool.max.connections=5
                """));

        assertThat(result.modules()).containsExactly("jms");
        assertThat(result.properties()).containsKey("quarkus.artemis.url");
        assertThat(result.properties())
                .containsEntry("quarkus.pooled-jms.pooling.enabled", "false")
                .doesNotContainKey("quarkus.pooled-jms.max-connections");
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void keepsUnknownJmsKind() {
        assertNotTranslated(
                QuarkusConfigExport.translate(
                        project(
                                """
                        forage.jms.kind=foo
                        forage.jms.broker.url=tcp://localhost:61616
                        """)),
                "'jms'");
    }

    @Test
    void keepsIdempotentRepositoryWithoutTransactions() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbc.idempotent.repository.enabled=true\n")),
                "'ds1' uses forage.ds1.jdbc.idempotent.repository.enabled");
    }

    @Test
    void keepsProjectIdempotentRepositoryDisabledAtExportTime() {
        System.setProperty("forage.ds1.jdbc.idempotent.repository.enabled", "false");

        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbc.idempotent.repository.enabled=true\n")),
                "'ds1' uses forage.ds1.jdbc.idempotent.repository.enabled");
    }

    @Test
    void ignoresIdempotentRepositoryEnabledAtExportTime() {
        System.setProperty("forage.ds1.jdbc.idempotent.repository.enabled", "true");

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1));

        assertThat(result.modules()).containsExactly("jdbc");
        assertThat(result.properties()).containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "client.id=c1",
                "ssl.enabled=true",
                "pool.connection.timeout.millis=5000",
                "artemis.call.timeout=100",
                "artemis.reconnect.attempts=5",
                "foo.bar=x",
                "username2=x"
            })
    void keepsUncoveredJmsKeys(String key) {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(
                        "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://localhost:61616\nforage.jms." + key)),
                "'jms' uses forage.jms." + key.substring(0, key.indexOf('=')) + ", not covered");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "foo.bar=x",
                "pool.idle.validation.timeout.minutes=9",
                "aggregation.repository.enabled=true",
                "pool.max.size.extra=1"
            })
    void keepsUncoveredJdbcKeys(String key) {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbc." + key)),
                "'ds1' uses forage.ds1.jdbc." + key.substring(0, key.indexOf('=')) + ", not covered");
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc, db.kind, postgresql",
        "jdbc, url, jdbc:postgresql://other/db",
        "jdbc, username, u",
        "jdbc, password, p",
        "jdbc, pool.initial.size, 4",
        "jdbc, pool.min.size, 4",
        "jdbc, pool.max.size, 7",
        "jdbc, pool.acquisition.timeout.seconds, 7",
        "jdbc, pool.validation.timeout.seconds, 7",
        "jdbc, pool.leak.timeout.minutes, 7",
        "jdbc, properties.ssl, true",
        "jms, kind, artemis",
        "jms, broker.url, tcp://other:61616",
        "jms, username, u",
        "jms, password, p",
        "jms, pool.enabled, true",
        "jms, pool.max.connections, 7",
        "jms, pool.max.sessions.per.connection, 7",
        "jms, pool.idle.timeout.millis, 7",
        "jms, pool.expiry.timeout.millis, 7",
        "jms, pool.block.if.full, true",
        "jms, pool.block.if.full.timeout.millis, 7"
    })
    void translatesEveryCoveredKey(String module, String key, String value) {
        String prefix = module.equals("jdbc") ? "forage.ds1.jdbc." : "forage.jms.";
        String base = module.equals("jdbc") ? DS1 : "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://h:61616\n";

        QuarkusConfigExport.Result result =
                QuarkusConfigExport.translate(project(base + prefix + key + "=" + value + "\n"));

        assertThat(result.modules()).containsExactly(module);
        assertThat(result.forageKeys()).contains(prefix + key);
    }

    @Test
    void ignoresKeysOutsideTheModulePrefix() {
        QuarkusConfigExport.Result result =
                QuarkusConfigExport.translate(project(DS1 + "forage.ds1.jdbcfoo.x=1\nforage.jmsX.foo=1\n"));

        assertThat(result.modules()).containsExactly("jdbc");
        assertThat(result.forageKeys()).noneMatch(key -> key.contains("jdbcfoo") || key.contains("jmsX"));
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    void keepsNamedJmsBrokers() {
        assertNotTranslated(
                QuarkusConfigExport.translate(
                        project(
                                """
                        forage.mq1.jms.kind=artemis
                        forage.mq1.jms.broker.url=tcp://localhost:61616
                        """)),
                "named configurations");
    }

    @Test
    void keepsIbmMq() {
        assertNotTranslated(
                QuarkusConfigExport.translate(
                        project(
                                """
                        forage.jms.kind=ibmmq
                        forage.jms.broker.url=localhost(1414)
                        """)),
                "no Quarkus equivalent");
    }

    /** Quarkus reads an empty value as not set, the Forage runtime uses it: translated or not, the export fails. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "forage.ds1.jdbc.password=",
                "forage.ds1.jdbc.username=",
                "forage.ds1.jdbc.properties.ssl=",
                "%prod.forage.ds1.jdbc.password=",
                "forage.ds1.jdbc.password={{env:DB_PASSWORD}}\nforage.ds1.jdbc.url=",
                "forage.ds1.jdbc.transaction.enabled=true\nforage.ds1.jdbc.password=",
                "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://localhost:61616\nforage.jms.password=",
                "forage.jms.kind=artemis\nforage.jms.broker.url=tcp://localhost:61616\nforage.jms.username=",
                "forage.mq1.jms.kind=artemis\nforage.mq1.jms.broker.url=tcp://localhost:61616\nforage.mq1.jms.password=",
                "%dev.forage.jms.password="
            })
    void failsOnEmptyValues(String line) {
        String key = line.substring(line.lastIndexOf('\n') + 1, line.length() - 1);
        assertThatThrownBy(() -> QuarkusConfigExport.translate(project(DS1.replace("password=secret\n", "") + line)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty value of " + key + " ")
                .hasMessageContaining("Set a value");
    }

    @Test
    void translatesBlankValues() {
        QuarkusConfigExport.Result result =
                QuarkusConfigExport.translate(project(DS1.replace("password=secret", "password=\\ ")));

        assertThat(result.properties()).containsEntry("quarkus.datasource.\"ds1\".password", " ");
    }

    /** The Forage Quarkus extension hands forage.* values to SmallRye, which expands them: the export fails. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "forage.ds1.jdbc.username={{env:DB_USER}}\nforage.ds1.jdbc.password=x${user.home}y",
                "forage.ds1.jdbc.transaction.enabled=true\nforage.ds1.jdbc.password=x$${user.home}y",
                "forage.ds1.jdbc.transaction.enabled=true\nforage.ds1.jdbc.password=a$$b",
                "forage.ds1.jdbc.transaction.enabled=true\nforage.ds1.jdbc.password=a\\\\$b",
                "forage.ds1.jdbc.transaction.enabled=true\nforage.ds1.jdbc.password=x${user.home",
                "%prod.forage.ds1.jdbc.password=${x}",
                "forage.mq1.jms.kind=artemis\nforage.mq1.jms.broker.url=tcp://localhost:61616\nforage.mq1.jms.password=${x}"
            })
    void failsOnExpressionsKeptOnForage(String lines) {
        String key = lines.substring(lines.lastIndexOf('\n') + 1, lines.lastIndexOf('='));
        String application = key.startsWith("%") ? lines : DS1.replace("forage.ds1.jdbc.password=secret\n", "") + lines;
        assertThatThrownBy(() -> QuarkusConfigExport.translate(project(application + "\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Quarkus would read " + key + " differently")
                .hasMessageContaining("stays as forage.* configuration (");
    }

    @Test
    void keepsDollarsSmallRyeDoesNotExpandOnForage() {
        assertNotTranslated(
                QuarkusConfigExport.translate(
                        project(DS1.replace("secret", "a$b$}c$") + "forage.ds1.jdbc.transaction.enabled=true\n")),
                "not covered");
    }

    @Test
    void translatesExpressionsAsWritten() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(
                project(
                        """
                forage.jms.kind=artemis
                forage.jms.broker.url=tcp://localhost:61616?x=${y}
                forage.jms.password=a$${b}\\\\$c$$d
                """));

        assertThat(result.modules()).containsExactly("jms");
        assertThat(result.properties())
                .containsEntry("quarkus.artemis.url", "tcp://localhost:61616?x=${y}")
                .containsEntry("quarkus.artemis.password", "a$${b}\\$c$$d");
    }

    @Test
    void translatesModuleOfAnyHook() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1), List.of(new FakeHook(null)));

        assertThat(result.modules()).containsExactly("jdbc");
        assertThat(result.properties()).containsEntry("fake", "test");
        assertThat(result.groups()).containsExactly("fake-group");
    }

    @Test
    void keepsModuleWithoutHook() {
        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1), List.of());

        assertThat(result.properties()).isEmpty();
        assertThat(result.modules()).isEmpty();
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void keepsModuleWhenHookTurnsItOff() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1), List.of(new FakeHook("is off"))), "'ds1' is off");
    }

    /** JDBC under a hook that replaces the translation. */
    private record FakeHook(String unsupported) implements QuarkusConfigHook<DataSourceFactoryConfig> {
        @Override
        public ForageModuleDescriptor<DataSourceFactoryConfig, ?> descriptor() {
            return new JdbcModuleDescriptor();
        }

        @Override
        public Set<String> covered() {
            return new JdbcQuarkusConfigHook().covered();
        }

        @Override
        public String unsupported(String name, DataSourceFactoryConfig config) {
            return unsupported;
        }

        @Override
        public Set<String> adjust(DataSourceFactoryConfig config, Map<String, String> translated) {
            translated.clear();
            translated.put("fake", config.username());
            return Set.of("fake-group");
        }
    }

    @Test
    void loadsProfileFilesAsProfileSpecific() throws IOException {
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);
        Files.writeString(
                dir.resolve("application-prod.properties"), "forage.ds1.jdbc.url=jdbc:postgresql://prod/db\n");

        QuarkusConfigExport.Project loaded = exported(dir);

        assertThat(loaded.application()).containsOnlyKeys("%prod.forage.ds1.jdbc.url");
        assertThat(loaded.files().get("forage-datasource-factory.properties")).containsKey("forage.ds1.jdbc.url");
    }

    /** Each profile value is checked under its own key: one profile cannot hide another's value (";" = new line). */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "%dev.forage.ds1.jdbc.password=ok;%prod.forage.ds1.jdbc.password=a${x} | %prod.forage.ds1.jdbc.password",
                "%prod.forage.ds1.jdbc.password=a${x};%dev.forage.ds1.jdbc.password=ok | %prod.forage.ds1.jdbc.password",
                "%test.forage.ds1.jdbc.password=a$$b;%prod.forage.ds1.jdbc.password=p;%dev.forage.ds1.jdbc.password=d"
                        + " | %test.forage.ds1.jdbc.password",
                "%dev,prod.forage.ds1.jdbc.password=a${x};%prod.forage.ds1.jdbc.password=p"
                        + " | %dev,prod.forage.ds1.jdbc.password",
                "%dev.forage.ds2.jdbc.url=ok;%prod.forage.ds2.jdbc.url=a${x} | %prod.forage.ds2.jdbc.url"
            })
    void failsOnAnExpressionInAnyProfile(String lines, String key) {
        assertThatThrownBy(() -> QuarkusConfigExport.translate(project(DS1 + lines.replace(";", "\n") + "\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Quarkus would read " + key + " differently");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "%dev.forage.ds1.jdbc.password=ok\n%prod.forage.ds1.jdbc.password=",
                "%prod.forage.ds1.jdbc.password=\n%dev.forage.ds1.jdbc.password=ok",
                "%test.forage.ds1.jdbc.password=t\n%prod.forage.ds1.jdbc.password=\n%dev.forage.ds1.jdbc.password=d"
            })
    void failsOnAnEmptyValueInAnyProfile(String lines) {
        assertThatThrownBy(() -> QuarkusConfigExport.translate(project(DS1 + lines + "\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty value of %prod.forage.ds1.jdbc.password");
    }

    @Test
    void failsOnAProfileValueSetTwice() throws IOException {
        Files.writeString(dir.resolve("application.properties"), "%prod.forage.ds1.jdbc.password=a\n");
        Files.writeString(dir.resolve("application-prod.properties"), "forage.ds1.jdbc.password=b\n");

        assertThatThrownBy(() -> exported(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("%prod.forage.ds1.jdbc.password is set to 'b' and to 'a'");

        Files.writeString(dir.resolve("application-prod.properties"), "forage.ds1.jdbc.password=a\n");
        assertThat(exported(dir).application()).containsOnlyKeys("%prod.forage.ds1.jdbc.password");
    }

    @Test
    void keepsDataSourceWhenAProfileNativeDbKindDiffers() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "%prod.quarkus.datasource.ds1.db-kind=mysql\n")),
                "'ds1' has %prod.quarkus.datasource.ds1.db-kind=mysql in application.properties");
    }

    @Test
    void writeKeepsUserKeysAndRemovesForageKeys() throws IOException {
        Files.writeString(
                dir.resolve("application.properties"),
                """
                camel.main.name=demo
                quarkus.datasource."ds1".jdbc.max-size=50
                forage.ds1.jdbc.url=jdbc:postgresql://localhost:5432/db
                """);
        Files.writeString(
                dir.resolve("forage-datasource-factory.properties"),
                """
                # datasource
                forage.ds1.jdbc.db.kind=postgresql
                forage.ds1.jdbc.username=test
                forage.ds1.jdbc.password=secret
                forage.ds1.jdbc.pool.min.size=4
                """);

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        Properties application = read(dir.resolve("application.properties"));
        assertThat(application)
                .containsEntry("camel.main.name", "demo")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.max-size", "50")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db")
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
        assertThat(application.stringPropertyNames()).noneMatch(key -> key.startsWith("forage."));
        assertThat(Files.readString(dir.resolve("forage-datasource-factory.properties")))
                .isEqualTo("# datasource\n");
    }

    @Test
    void writeKeepsUnquotedUserKeys() throws IOException {
        Files.writeString(dir.resolve("application.properties"), "quarkus.datasource.ds1.jdbc.min-size=7\n");
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        assertThat(read(dir.resolve("application.properties")))
                .containsEntry("quarkus.datasource.ds1.jdbc.min-size", "7")
                .doesNotContainKey("quarkus.datasource.\"ds1\".jdbc.min-size")
                .containsKey("quarkus.datasource.\"ds1\".jdbc.url");
    }

    /** SmallRye expands ${...}, $$ and \$: the block escapes every $, Quarkus reads the value the run translated. */
    @Test
    void writeEscapesWhatSmallRyeExpands() throws IOException {
        String password = "x${user.home}y$${a}$$b\\$c${d${e}}${f$";
        Files.writeString(
                dir.resolve("forage-datasource-factory.properties"),
                DS1.replace("password=secret", "password=" + password.replace("\\", "\\\\")));

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(exported(dir));
        QuarkusConfigExport.write(result, dir);

        SmallRyeConfig quarkus = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .addSystemSources()
                .withSources(new PropertiesConfigSource(read(dir.resolve("application.properties")), "app", 250))
                .build();
        assertThat(quarkus.getConfigValue("quarkus.datasource.\"ds1\".password").getValue())
                .isEqualTo(password);
        result.properties()
                .forEach((key, value) ->
                        assertThat(quarkus.getConfigValue(key).getValue()).isEqualTo(value));
    }

    @Test
    void writeDeletesFilesLeftEmpty() throws IOException {
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        assertThat(dir.resolve("forage-datasource-factory.properties")).doesNotExist();
        assertThat(read(dir.resolve("application.properties")))
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.min-size", "4");
    }

    @Test
    void applicationPropertiesWinOverTheModuleFile() throws IOException {
        Files.writeString(dir.resolve("application.properties"), "forage.ds1.jdbc.url=jdbc:h2:mem:fromapp\n");
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        assertThat(read(dir.resolve("application.properties")))
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:h2:mem:fromapp")
                .doesNotContainKey("forage.ds1.jdbc.url");
        assertThat(dir.resolve("forage-datasource-factory.properties")).doesNotExist();
    }

    @Test
    void ignoresFilesForageDoesNotRead() throws IOException {
        Files.writeString(dir.resolve("forage-datasource-factory.properties"), DS1);
        // Forage reads only application.properties and the module's own file
        Files.writeString(dir.resolve("other.properties"), "forage.ds1.jdbc.url=jdbc:h2:mem:other\n");
        Files.writeString(dir.resolve("forage-connectionfactory.properties"), "forage.ds1.jdbc.username=fromjms\n");

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(exported(dir));
        QuarkusConfigExport.write(result, dir);

        assertThat(result.properties())
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db")
                .containsEntry("quarkus.datasource.\"ds1\".username", "test");
        assertThat(Files.readString(dir.resolve("other.properties")))
                .isEqualTo("forage.ds1.jdbc.url=jdbc:h2:mem:other\n");
        assertThat(Files.readString(dir.resolve("forage-connectionfactory.properties")))
                .isEqualTo("forage.ds1.jdbc.username=fromjms\n");
    }

    @Test
    void ignoresDriverPropertiesFromTheHomeDirectory(@TempDir Path home) throws IOException {
        Files.writeString(
                home.resolve("forage-datasource-factory.properties"), "forage.ds1.jdbc.properties.ssl=true\n");
        System.setProperty("forage.home.dir", home.toString());
        try {
            QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(DS1));

            assertThat(result.modules()).containsExactly("jdbc");
            assertThat(result.properties())
                    .noneSatisfy((key, value) -> assertThat(key).contains("ssl"));
        } finally {
            System.clearProperty("forage.home.dir");
        }
    }

    @Test
    void keepsDataSourceWhenTheNativeDbKindDiffers() {
        assertNotTranslated(
                QuarkusConfigExport.translate(project(DS1 + "quarkus.datasource.ds1.db-kind=mysql\n")),
                "'ds1' has quarkus.datasource.ds1.db-kind=mysql in application.properties");

        assertThat(QuarkusConfigExport.translate(project(DS1 + "quarkus.datasource.\"ds1\".db-kind=postgresql\n"))
                        .modules())
                .containsExactly("jdbc");
    }

    @Test
    void writeRoundTripsSpecialCharacters() throws IOException {
        String password = " lead\\back\nnew\ttab#!=:é😀 trail ";
        Files.writeString(
                dir.resolve("forage-datasource-factory.properties"),
                DS1.replace("password=secret", "password=\\ lead\\\\back\\nnew\\ttab#!=:\\u00e9\\ud83d\\ude00 trail ")
                        + "forage.ds1.jdbc.properties.a\\ b\\:c=\\ v\n");

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(exported(dir));
        QuarkusConfigExport.write(result, dir);

        Properties application = read(dir.resolve("application.properties"));
        assertThat(application)
                .containsEntry("quarkus.datasource.\"ds1\".password", password)
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.additional-jdbc-properties.\"a b:c\"", " v");
        result.properties().forEach((key, value) -> assertThat(application).containsEntry(key, value));
    }

    /**
     * The exported application must see the value the Forage runtime reads from the project ({@link
     * PropertyFileLocator#readProperties}, ISO-8859-1): Camel JBang copies the module file byte for byte and rewrites
     * application.properties as UTF-8 with the values it read (ISO-8859-1), unescaped.
     */
    @ParameterizedTest
    @CsvSource({
        "ascii, 70617373",
        "latin1, 70e47373",
        "utf8, 70c3a47373",
        "escape, 705c75303065347373",
        "escapedUtf8, 705c75303063335c75303061347373",
        "utf8Supplementary, 70f09f98807373",
        "escapedSupplementary, 705c75643833645c75646530307373"
    })
    void writeKeepsTheValueTheRuntimeReads(String name, String hexValue) throws IOException {
        byte[] password = concat(
                "forage.ds1.jdbc.password=".getBytes(StandardCharsets.ISO_8859_1),
                HexFormat.of().parseHex(hexValue),
                new byte[] {'\n'});
        byte[] ds1 = DS1.replace("forage.ds1.jdbc.password=secret\n", "").getBytes(StandardCharsets.ISO_8859_1);
        byte[] comment = "# d\u00e9mo\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] camel = "camel.main.name=d\u00e9mo-\u00c3\u00a4-\ud83d\ude00\n".getBytes(StandardCharsets.UTF_8);
        for (String file : List.of("forage-datasource-factory.properties", "application.properties")) {
            byte[] project =
                    file.equals("application.properties") ? concat(ds1, password) : concat(comment, ds1, password);
            String runtime = PropertyFileLocator.readProperties(new ByteArrayInputStream(project))
                    .getProperty("forage.ds1.jdbc.password");
            Files.deleteIfExists(dir.resolve("forage-datasource-factory.properties"));
            if (file.equals("application.properties")) {
                Files.write(dir.resolve(file), concat(camel, camelRewrite(project)));
            } else {
                Files.write(dir.resolve(file), project);
                Files.write(dir.resolve("application.properties"), camel);
            }

            QuarkusConfigExport.Result result = QuarkusConfigExport.translate(exported(dir));
            QuarkusConfigExport.write(result, dir);

            assertThat(read(dir.resolve("application.properties")))
                    .as(name + " in " + file + ": " + result.messages())
                    .containsEntry("quarkus.datasource.\"ds1\".password", runtime);
            // the lines left keep their bytes
            assertThat(Files.readAllBytes(dir.resolve("application.properties")))
                    .startsWith(camel);
            if (file.equals("forage-datasource-factory.properties")) {
                assertThat(Files.readAllBytes(dir.resolve(file))).isEqualTo(comment);
            }
        }
    }

    @Test
    void writeKeepsThePropertyValue() throws IOException {
        // --property forage.ds1.jdbc.password=p\u00e4ss: Camel JBang writes the string as UTF-8
        Files.write(
                dir.resolve("application.properties"),
                (DS1.replace("secret", "p\u00e4ss\ud83d\ude00")).getBytes(StandardCharsets.UTF_8));

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        assertThat(read(dir.resolve("application.properties")))
                .containsEntry("quarkus.datasource.\"ds1\".password", "p\u00e4ss\ud83d\ude00");
    }

    @Test
    void writeKeepsByteOrderMarks() throws IOException {
        byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        // as the runtime and Quarkus, the key after the mark is not forage.ds1.jdbc.url
        byte[] application = concat(bom, "forage.ds1.jdbc.url=jdbc:h2:mem:bom\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("application.properties"), application);
        byte[] module = concat(bom, "# datasource\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(
                dir.resolve("forage-datasource-factory.properties"),
                concat(module, DS1.getBytes(StandardCharsets.ISO_8859_1)));

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(exported(dir));
        QuarkusConfigExport.write(result, dir);

        assertThat(result.properties())
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.url", "jdbc:postgresql://localhost:5432/db");
        assertThat(Files.readAllBytes(dir.resolve("application.properties"))).startsWith(application);
        assertThat(Files.readAllBytes(dir.resolve("forage-datasource-factory.properties")))
                .isEqualTo(module);
    }

    /** What Camel JBang exports for a project application.properties: the values it read, as UTF-8. */
    private static byte[] camelRewrite(byte[] project) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(project));
        StringBuilder content = new StringBuilder();
        properties.stringPropertyNames().forEach(key -> content.append(key)
                .append('=')
                .append(properties.getProperty(key))
                .append('\n'));
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writeRemovesEscapedKeys() throws IOException {
        Files.writeString(dir.resolve("application.properties"), DS1 + "forage.ds1.jdbc.properties.a\\=b=1\n");

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        Properties application = read(dir.resolve("application.properties"));
        assertThat(application.stringPropertyNames()).noneMatch(key -> key.startsWith("forage."));
        assertThat(application)
                .containsEntry("quarkus.datasource.\"ds1\".jdbc.additional-jdbc-properties.\"a=b\"", "1");
    }

    @Test
    void writeUsesOneLineSeparator() throws IOException {
        Files.writeString(
                dir.resolve("application.properties"), "camel.main.name=demo\r\n" + DS1.replace("\n", "\r\n"));

        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);

        assertThat(Files.readString(dir.resolve("application.properties")))
                .doesNotContain("\r")
                .startsWith("camel.main.name=demo\n");
    }

    @Test
    void secondExportChangesNothing() throws IOException {
        Files.writeString(dir.resolve("application.properties"), DS1);
        QuarkusConfigExport.write(QuarkusConfigExport.translate(exported(dir)), dir);
        String once = Files.readString(dir.resolve("application.properties"));

        QuarkusConfigExport.Result again = QuarkusConfigExport.translate(exported(dir));
        QuarkusConfigExport.write(again, dir);

        assertThat(again.modules()).isEmpty();
        assertThat(Files.readString(dir.resolve("application.properties"))).isEqualTo(once);
    }

    @Test
    void translatesManyDataSources() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            content.append(DS1.replace("ds1", "ds" + i));
        }

        QuarkusConfigExport.Result result = QuarkusConfigExport.translate(project(content.toString()));

        assertThat(result.forageKeys()).hasSize(1500);
        assertThat(result.properties()).containsEntry("quarkus.datasource.\"ds299\".jdbc.min-size", "4");
    }

    @Test
    void removeKeysDropsContinuationLines() {
        List<String> kept = QuarkusConfigExport.removeKeys(
                List.of("a=1", "forage.x=one \\", "  two", "# comment \\", "b = 2"), Set.of("forage.x"));

        assertThat(kept).containsExactly("a=1", "# comment \\", "b = 2");
    }

    private static void assertNotTranslated(QuarkusConfigExport.Result result, String reason) {
        assertThat(result.properties()).isEmpty();
        assertThat(result.forageKeys()).isEmpty();
        assertThat(result.modules()).isEmpty();
        assertThat(result.messages())
                .singleElement()
                .asString()
                .contains("stays as forage.*")
                .contains(reason);
    }

    private static QuarkusConfigExport.Project project(String application) {
        return new QuarkusConfigExport.Project(properties(application), Map.of());
    }

    private static Properties properties(String content) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(content));
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exported resources, as the export loads them. */
    private static QuarkusConfigExport.Project exported(Path dir) throws IOException {
        return QuarkusConfigExport.load(dir, QuarkusConfigExport.EXPORTED_APPLICATION);
    }

    /** Reads application.properties as Quarkus does (SmallRye {@code ConfigSourceUtil.urlToMap}: UTF-8 reader). */
    private static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
