# Embeddings

Embedding models convert text into vector representations for similarity search and RAG.

## Available Providers

{{ forage_beans_table("Embedding Model", "Embeddings Model") }}

## Embedding Model Beans

| Plain Camel / JBang | Spring Boot | Quarkus |
|---|---|---|
| `forage-embedding-model` | not yet | not yet |

`forage.embeddings.kind` creates an `EmbeddingModel` bean named `embeddingModel`.
`forage.<name>.embeddings.kind` creates a bean named `<name>`; its provider properties take the
same prefix, e.g. `forage.<name>.ollama.embedding.model.name`. Put the keys in `application.properties`,
or split them into `forage-embedding-model.properties` (kind) and `forage-embedding-model-ollama.properties`
(provider).

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

{{ forage_properties("Embedding Model") }}

## Agent Embeddings

An agent with RAG builds its own embedding model, with the provider of its chat model kind
(see the [RAG example](../../examples/ai/rag.md)):

```properties
forage.myAgent.agent.embedding.model.name=nomic-embed-text
forage.myAgent.agent.embedding.model.base.url=http://localhost:11434
```
