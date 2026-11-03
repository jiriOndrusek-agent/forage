package io.kaoto.forage.quarkus.vectordb;

import io.kaoto.forage.vectordb.store.EmbeddingStoreCreator;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

@Recorder
public class ForageEmbeddingStoreRecorder {
    public RuntimeValue<EmbeddingStore<TextSegment>> createStore(String prefix) {
        return new RuntimeValue<>(
                EmbeddingStoreCreator.create(prefix, Thread.currentThread().getContextClassLoader()));
    }
}
