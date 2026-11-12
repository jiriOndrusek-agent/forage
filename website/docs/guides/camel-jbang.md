# Camel JBang

The Forage plugin for [Camel JBang](https://camel.apache.org/manual/camel-jbang.html) provides dependency resolution, property validation, hot reload, and export to production runtimes.

## Installation

=== "Camel LTS ({{ camel_lts_version }})"

    ```bash
    camel plugin add forage --gav io.kaoto.forage:camel-jbang-plugin-forage:{{ forage_version }}
    ```

=== "Camel Previous LTS ({{ camel_previous_lts_version }})"

    ```bash
    camel plugin add forage --gav io.kaoto.forage:camel-jbang-plugin-forage:{{ forage_previous_lts_version }}
    ```

=== "Camel Latest ({{ camel_latest_version }})"

    ```bash
    camel plugin add forage --gav io.kaoto.forage:camel-jbang-plugin-forage:{{ forage_latest_version }}
    ```

## Running Routes

```bash
camel run *
```

The `*` glob picks up all route files and properties in the current directory. You can also specify files explicitly:

```bash
camel run route.camel.yaml application.properties
```

With the Forage plugin installed, the standard `camel run` command automatically resolves all required Forage dependencies based on your configuration — no `--dep` flags needed.

## Property Validation

The plugin validates your Forage properties against the catalog before running. This catches configuration errors early.

### Typo Detection

```properties
# Typo in property name
forage.myDb.jdbc.usernam=admin
```

```text
[UNKNOWN_PROPERTY] in application.properties
  Property: forage.myDb.jdbc.usernam
  Unknown property 'usernam' for factory 'jdbc'. Did you mean 'username'?
```

### Invalid Bean Values

```properties
# Invalid database kind
forage.myDb.jdbc.db.kind=postgresqll
```

```text
[INVALID_BEAN_VALUE] in application.properties
  Property: forage.myDb.jdbc.db.kind
  Unknown database 'postgresqll'. Did you mean 'postgresql'?
  Valid options: postgresql, mysql, mariadb, db2, h2, oracle
```

### Unknown Properties

```properties
# Property that doesn't exist
forage.myDb.jdbc.invalid.property=value
```

```text
[UNKNOWN_PROPERTY] in application.properties
  Property: forage.myDb.jdbc.invalid.property
  Unknown property 'invalid.property' for factory 'jdbc'
```

## Hot Reload

In dev mode, Forage automatically recreates beans when `.properties` files change — without restarting the application:

```bash
camel run --dev *
```

Forage hooks into Camel's route watcher reload strategy via the `ContextServicePlugin.onReload()` SPI. When Camel detects a file change, Forage refreshes beans **before** routes are reloaded, ensuring deterministic ordering in a single thread.

The reload cycle is:

1. **Cleanup** — each `BeanFactory` unbinds old beans from the registry
2. **Clear config** — the `ConfigStore` cache is cleared so values are re-read from disk
3. **Reconfigure** — each `BeanFactory` re-reads configuration and binds new beans

After the plugin reload completes, Camel proceeds to reload routes, which pick up the new beans automatically.

### What Can Be Hot-Reloaded

- API keys, connection URLs, model names
- Connection pool sizes, timeouts
- Any `forage.*` property value

### What Requires a Restart

- **Provider type changes** — e.g., changing `db.kind` from `postgresql` to `mysql` (requires different JARs)
- **Adding new factory types** — e.g., adding JDBC when only AI was configured
- **Environment variable changes** — only `.properties` file changes are detected

## Exporting to Production

Export your project to Spring Boot or Quarkus with all Forage dependencies included:

=== "Spring Boot"

    ```bash
    camel export --runtime=spring-boot --directory=./my-app
    ```

=== "Quarkus"

    ```bash
    camel export --runtime=quarkus --directory=./my-app
    ```

{% raw %}
### Quarkus Properties

The Quarkus export writes the JDBC datasources and the default Artemis JMS connection factory as Quarkus
properties (`quarkus.datasource.*`, `quarkus.artemis.*`, `quarkus.pooled-jms.*`) in
`src/main/resources/META-INF/microprofile-config.properties`, instead of their `forage.*` properties. A comment above
each block lists the original properties. That file has a lower ordinal (100) than `application.properties`, so the
`quarkus.*` values set there still win.

A configuration keeps its `forage.*` properties, which the Forage Quarkus extension handles at runtime, when:

- a property has no 1:1 Quarkus equivalent, for example transactions, aggregation and idempotent repositories,
  named or IBM MQ JMS connection factories.
- a value uses a `{{...}}` placeholder, which Quarkus cannot resolve, or non-ASCII characters. `${...}` expressions
  are copied as they are.
- a value is profile-specific (`application-<profile>.properties`, `%<profile>.forage.*`), or comes from outside the
  exported files: an environment variable, a system property or `~/.forage`.
- it is the default configuration of a module that also has named ones.

The export prints a warning for each of them. The other modules always keep their `forage.*` properties.

Override translated values with Quarkus configuration, for example `QUARKUS_DATASOURCE__DS1__PASSWORD`. `FORAGE_*`
variables still override them only when they set the whole configuration (e.g. `FORAGE_DS1_JDBC_DB_KIND`, `_URL`,
`_USERNAME` and `_PASSWORD`): with only some of them, Forage finds an incomplete configuration and fails at startup,
with an error that points to the Quarkus configuration.
Even then, Forage's runtime configuration takes over that datasource, so the other translated settings (e.g. pool
`max-size`) fall back to Forage defaults. Set them through `FORAGE_*` variables too, or override with `QUARKUS_*`.
{% endraw %}

## Testing Connections

Verify datasource connectivity without starting routes:

```bash
camel forage datasource test-connection
```

## Configuration Commands

Read Forage configuration and output bean definitions as JSON:

```bash
camel forage config read
```

Write configuration from JSON input:

```bash
camel forage config write --input '{"forage.myDb.jdbc.url":"jdbc:postgresql://localhost:5432/mydb"}'
```
