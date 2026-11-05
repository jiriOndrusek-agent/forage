package io.kaoto.forage.quarkus.embeddings;

import io.kaoto.forage.models.embeddings.common.EmbeddingModelCreator;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import dev.langchain4j.model.embedding.EmbeddingModel;

@Recorder
public class ForageEmbeddingModelRecorder {
    public RuntimeValue<EmbeddingModel> createModel(String prefix) {
        return new RuntimeValue<>(
                EmbeddingModelCreator.create(prefix, Thread.currentThread().getContextClassLoader()));
    }
}
