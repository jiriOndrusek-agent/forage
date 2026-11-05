# Embeddings

Embedding models convert text into vector representations for similarity search and RAG.

## Available Providers

{{ forage_beans_table("Embedding Model", "Embeddings Model") }}

## Embedding Model Beans

| Plain Camel / JBang | Spring Boot | Quarkus |
|---|---|---|
| `forage-embedding-model` | `forage-embedding-model-starter` | `forage-quarkus-embedding-model` |

`forage.embeddings.kind` creates an `EmbeddingModel` bean named `embeddingModel`.
`forage.<name>.embeddings.kind` creates a bean named `<name>`; named beans replace the default one.
Provider properties take the same prefix, e.g. `forage.<name>.ollama.embedding.model.name`. Put the keys
in `application.properties`, or split them into `forage-embedding-model.properties` (kind) and
`forage-embedding-model-ollama.properties` (provider).

```properties
forage.embeddings.kind=ollama
forage.ollama.embedding.model.name=nomic-embed-text
forage.ollama.embedding.model.base.url=http://localhost:11434
```

The `langchain4j-embeddings` component autowires a single `EmbeddingModel` bean. With several
beans, select one by name:

```yaml
- to:
    uri: langchain4j-embeddings:embed
    parameters:
      embeddingModel: "#bean:docs"
```

Give each bean its own name: a model and a store with the same name (`forage.docs.embeddings.kind`
and `forage.docs.vectordb.kind`), or a model named like any other bean, fail the app at startup
(Quarkus: at build time).

{{ forage_properties("Embedding Model") }}

To store the embeddings, see [Embedding Store Beans](vector-databases.md#embedding-store-beans).

## Agent Embeddings

An agent with RAG builds its own embedding model, with the provider of its chat model kind
(see the [RAG example](../../examples/ai/rag.md)):

```properties
forage.myAgent.agent.embedding.model.name=nomic-embed-text
forage.myAgent.agent.embedding.model.base.url=http://localhost:11434
```

Keys under `forage.[<name>.]agent.` belong to the agent: `forage.myAgent.agent.embeddings.kind` of older docs
creates no model bean.
