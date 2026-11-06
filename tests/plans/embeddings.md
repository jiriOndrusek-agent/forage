# Test Plan: Embedding Model and Store Beans

Verify `forage.[<name>.]embeddings.kind` and `forage.[<name>.]vectordb.kind`: a route ingests two
texts into an in-memory store and finds the closest one. An Ollama stand-in answers `/api/embed`, so
no model or container is needed. Then repeat with exported runtimes.

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java | 17+ | `java -version` |
| Camel JBang | Matching Forage's Camel version | `camel version` |
| curl | Any | `curl --version` |

Install the locally built Forage plugin following [common/forage-run.md](common/forage-run.md).
Run the phases in the same shell. `OLLAMA_PORT` defaults to 11500. To use a real Ollama instead of
the stand-in, set `OLLAMA_URL` (e.g. `http://localhost:11434`) and `EMBEDDING_MODEL` to a pulled
embedding model (e.g. `all-minilm`), and skip Phase 1.

## Phase 1: Start the Ollama stand-in

Each word of a text counts in one of 64 dimensions, so texts that share words are close.

```bash
EMB_PLAN_DIR=$(mktemp -d)
OLLAMA_PORT=${OLLAMA_PORT:-11500}
cat > "${EMB_PLAN_DIR}/OllamaStub.java" <<'EOF'
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaStub {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.createContext("/api/embed", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher input = Pattern.compile("\"input\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(body);
            List<String> vectors = new ArrayList<>();
            Matcher text = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(input.find() ? input.group(1) : "");
            while (text.find()) {
                double[] vector = new double[64];
                for (String word : text.group(1).toLowerCase().split("[^a-z]+")) {
                    if (!word.isEmpty()) {
                        vector[Math.floorMod(word.hashCode(), 64)]++;
                    }
                }
                double norm = Math.sqrt(Arrays.stream(vector).map(x -> x * x).sum());
                vectors.add(Arrays.toString(Arrays.stream(vector).map(x -> norm == 0 ? 0 : x / norm).toArray()));
            }
            byte[] response = ("{\"model\":\"stub\",\"embeddings\":" + vectors + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }
}
EOF
java "${EMB_PLAN_DIR}/OllamaStub.java" "${OLLAMA_PORT}" > "${EMB_PLAN_DIR}/stub.log" 2>&1 &
STUB_PID=$!
OLLAMA_URL=${OLLAMA_URL:-http://127.0.0.1:${OLLAMA_PORT}}
EMBEDDING_MODEL=${EMBEDDING_MODEL:-stub}

for attempt in $(seq 1 30); do
  curl -fsS -X POST "${OLLAMA_URL}/api/embed" -d '{"model":"stub","input":["ping"]}' 2>/dev/null |
    grep -q '"embeddings"' && break
  sleep 1
done
curl -fsS -X POST "${OLLAMA_URL}/api/embed" -d '{"model":"stub","input":["ping"]}' |
  grep -q '"embeddings"' && echo 'PASS: Ollama stand-in ready' || echo 'FAIL: Ollama stand-in not ready'
```

## Phase 2: Default beans from the module properties files

```bash
mkdir -p "${EMB_PLAN_DIR}/default"
cd "${EMB_PLAN_DIR}/default"
echo 'forage.embeddings.kind=ollama' > forage-embedding-model.properties
cat > forage-embedding-model-ollama.properties <<EOF
forage.ollama.embedding.model.name=${EMBEDDING_MODEL}
forage.ollama.embedding.model.base.url=${OLLAMA_URL}
EOF
echo 'forage.vectordb.kind=in-memory-store' > forage-embedding-store.properties
cat > route.camel.yaml <<'EOF'
- route:
    id: ingest-and-search
    from:
      uri: timer:embeddings
      parameters:
        repeatCount: 1
        delay: 1000
      steps:
        - setBody:
            constant: Forage creates Camel beans from properties
        - to:
            uri: direct:add
        - setBody:
            constant: Quarkus starts fast
        - to:
            uri: direct:add
        - setBody:
            constant: Which beans does Forage create?
        - to:
            uri: langchain4j-embeddings:embed
        - to:
            uri: langchain4j-embeddingstore:docs
            parameters:
              action: SEARCH
              maxResults: 1
              returnTextContent: true
        - log:
            message: "Search result: ${body[0]}"
- route:
    id: add
    from:
      uri: direct:add
      steps:
        - to:
            uri: langchain4j-embeddings:embed
        - to:
            uri: langchain4j-embeddingstore:docs
            parameters:
              action: ADD
EOF

camel forage run *.properties route.camel.yaml > output.log 2>&1 &
CAMEL_PID=$!
for attempt in $(seq 1 120); do
  grep -q 'Search result:' output.log 2>/dev/null && break
  sleep 1
done
if grep -q 'Search result: Forage creates Camel beans from properties' output.log; then
  echo 'PASS: default embeddingModel and embeddingStore beans found the closest text'
else
  echo 'FAIL: expected search result missing'
  cat output.log
fi
kill "${CAMEL_PID}" 2>/dev/null || true
wait "${CAMEL_PID}" 2>/dev/null || true
cd "${EMB_PLAN_DIR}"
```

## Phase 3: Named beans from application.properties

Two models and two stores; the route selects `docs` and `notes` with `#bean:<name>`.

```bash
mkdir -p "${EMB_PLAN_DIR}/named"
cd "${EMB_PLAN_DIR}/named"
cat > application.properties <<EOF
forage.docs.embeddings.kind=ollama
forage.docs.ollama.embedding.model.name=${EMBEDDING_MODEL}
forage.docs.ollama.embedding.model.base.url=${OLLAMA_URL}
forage.faq.embeddings.kind=ollama
forage.faq.ollama.embedding.model.base.url=${OLLAMA_URL}
forage.notes.vectordb.kind=in-memory-store
forage.archive.vectordb.kind=in-memory-store
EOF
cat > route.camel.yaml <<'EOF'
- route:
    id: ingest-and-search
    from:
      uri: timer:embeddings
      parameters:
        repeatCount: 1
        delay: 1000
      steps:
        - setBody:
            constant: Forage creates Camel beans from properties
        - to:
            uri: direct:add
        - setBody:
            constant: Quarkus starts fast
        - to:
            uri: direct:add
        - setBody:
            constant: Which beans does Forage create?
        - to:
            uri: langchain4j-embeddings:embed
            parameters:
              embeddingModel: "#bean:docs"
        - to:
            uri: langchain4j-embeddingstore:notes
            parameters:
              action: SEARCH
              embeddingStore: "#bean:notes"
              maxResults: 1
              returnTextContent: true
        - log:
            message: "Search result: ${body[0]}"
- route:
    id: add
    from:
      uri: direct:add
      steps:
        - to:
            uri: langchain4j-embeddings:embed
            parameters:
              embeddingModel: "#bean:docs"
        - to:
            uri: langchain4j-embeddingstore:notes
            parameters:
              action: ADD
              embeddingStore: "#bean:notes"
EOF

camel forage run application.properties route.camel.yaml > output.log 2>&1 &
CAMEL_PID=$!
for attempt in $(seq 1 120); do
  grep -q 'Search result:' output.log 2>/dev/null && break
  sleep 1
done
if grep -q 'Search result: Forage creates Camel beans from properties' output.log &&
   grep -q "Registered EmbeddingModel bean 'faq'" output.log &&
   grep -q "Registered EmbeddingStore bean 'archive'" output.log; then
  echo 'PASS: named beans created and selected by name'
else
  echo 'FAIL: named beans missing or not selected'
  cat output.log
fi
kill "${CAMEL_PID}" 2>/dev/null || true
wait "${CAMEL_PID}" 2>/dev/null || true
cd "${EMB_PLAN_DIR}"
```

## Phase 4: Reject a typo in the kind

```bash
mkdir -p "${EMB_PLAN_DIR}/typo"
cp default/*.properties default/route.camel.yaml typo/
echo 'forage.vectordb.kind=pgvectr' > typo/forage-embedding-store.properties
if camel forage run --strict typo/*.properties typo/route.camel.yaml > typo/output.log 2>&1; then
  echo 'FAIL: strict mode accepted an unknown embedding store kind'
else
  grep -q "Did you mean 'pgvector'?" typo/output.log &&
    echo 'PASS: strict mode rejected the typo' || { echo 'FAIL: no suggestion'; cat typo/output.log; }
fi
```

## Phase 5: Exported runtimes

Repeat Phase 2 and Phase 3 with `camel forage run --runtime=spring-boot ...` and
`camel forage run --runtime=quarkus ...`, and allow up to 300 polling attempts for the export
and build. The Spring Boot log shows `Registered EmbeddingModel bean definition 'embeddingModel'`.

## Phase 6: Cleanup

```bash
kill "${CAMEL_PID}" 2>/dev/null || true
kill "${STUB_PID}" 2>/dev/null || true
wait "${STUB_PID}" 2>/dev/null || true
echo "Test artifacts retained in ${EMB_PLAN_DIR}"
```

## Summary

| Phase | Test ID | Test Name | Priority |
|-------|---------|-----------|----------|
| 1 | 1.1 | Start the Ollama stand-in | Critical |
| 2 | 2.1 | Default model and in-memory store find the closest text | Critical |
| 3 | 3.1 | Named models and stores selected with `#bean:<name>` | Critical |
| 4 | 4.1 | Strict mode rejects an unknown store kind | High |
| 5 | 5.1 | Phases 2 and 3 on Spring Boot | High |
| 5 | 5.2 | Phases 2 and 3 on Quarkus | High |
| 6 | 6.1 | Cleanup | Medium |
