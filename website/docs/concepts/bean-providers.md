# Bean Providers

Forage automatically creates and registers beans in the Camel registry based on your configuration. You never write Java code to instantiate a datasource, connection factory, or AI model — you just declare what you need in a properties file.

## Named Beans

Every Forage bean has a name. The name you choose in the properties file becomes the bean name in the Camel registry:

```properties
# "ordersDb" is the bean name
forage.ordersDb.jdbc.db.kind=postgresql
forage.ordersDb.jdbc.url=jdbc:postgresql://localhost:5432/orders
forage.ordersDb.jdbc.username=admin
forage.ordersDb.jdbc.password=secret
```

Reference it in your routes with `#ordersDb`:

```yaml
- to:
    uri: sql
    parameters:
      query: select * from orders
      dataSource: "#ordersDb"
```

You can create as many beans as you need — just use different names:

```properties
# Two databases, two beans
forage.ordersDb.jdbc.db.kind=postgresql
forage.ordersDb.jdbc.url=jdbc:postgresql://localhost:5432/orders

forage.inventoryDb.jdbc.db.kind=mysql
forage.inventoryDb.jdbc.url=jdbc:mysql://localhost:3306/inventory
```

This pattern is the same for all Forage modules — JDBC, JMS, AI agents, vector databases, and more.

## How Discovery Works

When Forage starts, it:

1. **Scans properties** for Forage configuration entries (e.g., `forage.*.jdbc.*`, `forage.*.agent.*`)
2. **Identifies the provider** based on the configuration (e.g., `db.kind=postgresql` selects the PostgreSQL provider)
3. **Creates the bean** using the provider — a pooled datasource, a chat model, a connection factory, etc.
4. **Registers the bean** in the Camel registry under the name you chose

This discovery uses Java's [ServiceLoader](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ServiceLoader.html) mechanism. When you add a Forage module to your classpath (e.g., `forage-jdbc-postgresql`), its provider is automatically available — no manual wiring required.

## Provider Types

Forage includes providers for several categories of beans:

| Category | What It Creates | Example Properties Prefix |
|---|---|---|
| **JDBC** | Pooled datasources (`DataSource`) | `forage.<name>.jdbc.*` |
| **JMS** | Connection factories (`ConnectionFactory`) | `forage.<name>.jms.*` |
| **AI Chat Models** | LLM clients (`ChatModel`) | `forage.<name>.agent.*` |
| **Chat Memory** | Conversation storage | `forage.<name>.agent.memory.*` |
| **Embeddings** | Text embedding models (`EmbeddingModel`) | `forage.<name>.embeddings.*` |
| **Vector Databases** | Embedding stores (`EmbeddingStore`) | `forage.<name>.vectordb.*` |

Each category has multiple implementations. For example, JDBC supports PostgreSQL, MySQL, H2, and more. The `kind` or `model.kind` property selects the implementation:

```properties
# Selects the PostgreSQL provider
forage.myDb.jdbc.db.kind=postgresql

# Selects the Ollama model provider
forage.myAgent.agent.model.kind=ollama
```

## Conditional Beans

Some providers create additional beans based on your configuration. For example, when you enable transactions on a JDBC datasource:

```properties
forage.myDb.jdbc.db.kind=postgresql
forage.myDb.jdbc.url=jdbc:postgresql://localhost:5432/mydb
forage.myDb.jdbc.transaction.enabled=true
```

Forage automatically registers transaction policy beans (`PROPAGATION_REQUIRED`, `MANDATORY`, etc.) that you can use in transacted routes:

```yaml
- route:
    from:
      uri: jms:queue:orders
      steps:
        - policy:
            ref: PROPAGATION_REQUIRED
        - to:
            uri: sql
            parameters:
              query: "INSERT INTO orders ..."
              dataSource: "#myDb"
```

Similarly, you can declare aggregation repositories and idempotent repositories directly in your datasource configuration:

```properties
forage.myDb.jdbc.aggregation.repository.name=myAggRepo
forage.myDb.jdbc.idempotent.repository.name=myIdempotentRepo
```

These are registered as separate beans (`#myAggRepo`, `#myIdempotentRepo`) backed by the same datasource.
