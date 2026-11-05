# Vector Databases

Vector databases store embeddings for similarity search, used in RAG (Retrieval-Augmented Generation) pipelines.

## Available Providers

{{ forage_beans_table("Embedding Store", "Embedding Store") }}

## Embedding Store Beans

| Plain Camel / JBang | Spring Boot | Quarkus |
|---|---|---|
| `forage-embedding-store` | `forage-embedding-store-starter` | `forage-quarkus-embedding-store` |

`forage.vectordb.kind` creates an `EmbeddingStore` bean named `embeddingStore`.
`forage.<name>.vectordb.kind` creates a bean named `<name>`; named beans replace the default one.
Provider properties keep their keys and take the same prefix, e.g. `forage.<name>.pgvector.host`.
Put them in `application.properties` or in the provider file, e.g. `forage-vectordb-pgvector.properties`.

```properties
forage.vectordb.kind=pgvector
forage.pgvector.host=localhost
forage.pgvector.database=vectors
forage.pgvector.user=forage
forage.pgvector.dimension=384
```

Set `FORAGE_PGVECTOR_PASSWORD` in the environment.

The `in-memory-store` bean starts empty. Its seed file (`in.memory.store.file.source`) needs an
embedding model and is used by agents only.

The `langchain4j-embeddingstore` component autowires a single `EmbeddingStore` bean, and a single
`EmbeddingModel` bean to embed text bodies. A route can ingest documents that another route searches:

```yaml
- to:
    uri: langchain4j-embeddings:embed
- to:
    uri: langchain4j-embeddingstore:docs
    parameters:
      action: ADD
```

With several beans, select one with `embeddingStore: "#bean:<name>"`.

## RAG Configuration

{{ forage_bean_properties("Agent", "RAG", "defaultRag") }}

## Provider Notes

### In-Memory Store

Zero-dependency store for development. Loads documents from a file, chunks them, and embeds them at startup.

```properties
forage.myAgent.agent.in.memory.store.file.source=knowledge-base.txt
forage.myAgent.agent.in.memory.store.max.size=300
forage.myAgent.agent.in.memory.store.overlap.size=100
```

These properties are enough for `camel forage run` and `camel forage export` to add the `forage-in-memory-store` module and, since the file source turns RAG on, `forage-default-retrieval-augmentor`. Setting `forage.myAgent.agent.embedding.store.kind=in-memory-store` selects the store explicitly.

### Pinecone

Supports automatic index creation when `create.index=true` is set alongside `dimension`, `cloud`, and `region`.

### Milvus

The `database.name` property is now wired to the builder. Port defaults to `19530` if not set.

### Weaviate

Port defaults to `8080` (REST) and `50051` (gRPC). gRPC is disabled by default (`use.grpc.for.inserts=false`).

### Qdrant

Port defaults to `6334` if not set.

### PgVector

Port defaults to `5432` if not set.
