package io.kaoto.forage.vectordb.store;

import java.util.ArrayList;
import java.util.List;
import io.kaoto.forage.core.ai.EmbeddingStoreProvider;
import io.kaoto.forage.core.annotations.ForageBean;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

@ForageBean(value = TestEmbeddingStoreProvider.KIND, description = "Test-only embedding store provider")
public class TestEmbeddingStoreProvider implements EmbeddingStoreProvider {
    static final String KIND = "test-store";
    static final List<String> IDS = new ArrayList<>();

    @Override
    public EmbeddingStore<TextSegment> create(String id) {
        IDS.add(id);
        return new InMemoryEmbeddingStore<>();
    }
}
