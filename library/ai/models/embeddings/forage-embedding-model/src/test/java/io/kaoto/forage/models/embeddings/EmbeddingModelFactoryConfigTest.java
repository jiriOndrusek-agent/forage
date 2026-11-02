package io.kaoto.forage.models.embeddings;

import java.nio.file.Files;
import java.nio.file.Path;
import io.kaoto.forage.core.util.config.ConfigStore;
import io.kaoto.forage.core.util.config.MissingConfigException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelFactoryConfigTest {
    @TempDir
    Path directory;

    @AfterEach
    void reset() {
        System.clearProperty("forage.config.dir");
        System.clearProperty("forage.embeddings.kind");
        System.clearProperty("forage.docs.embeddings.kind");
        ConfigStore.getInstance().reload();
    }

    @Test
    void loadsDefaultAndNamedKindsWithSystemOverride() throws Exception {
        Files.writeString(
                directory.resolve("forage-embedding-model.properties"),
                "forage.embeddings.kind=ollama\nforage.docs.embeddings.kind=other\n");
        System.setProperty("forage.config.dir", directory.toString());

        assertThat(new EmbeddingModelFactoryConfig().kind()).isEqualTo("ollama");
        assertThat(new EmbeddingModelFactoryConfig("docs").kind()).isEqualTo("other");

        System.setProperty("forage.docs.embeddings.kind", " ollama ");
        assertThat(new EmbeddingModelFactoryConfig("docs").kind()).isEqualTo("ollama");
    }

    @Test
    void requiresKind() {
        assertThatThrownBy(() -> new EmbeddingModelFactoryConfig().kind())
                .isInstanceOf(MissingConfigException.class)
                .hasMessageContaining("forage.embeddings.kind");

        System.setProperty("forage.docs.embeddings.kind", " ");
        assertThatThrownBy(() -> new EmbeddingModelFactoryConfig("docs").kind())
                .isInstanceOf(MissingConfigException.class)
                .hasMessageContaining("forage.docs.embeddings.kind");
    }
}
