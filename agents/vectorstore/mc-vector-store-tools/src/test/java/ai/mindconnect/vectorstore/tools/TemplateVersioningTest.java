package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.common.StaleVersionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A template saved from a form opened before another save is refused. */
class TemplateVersioningTest {

    @Test
    void aSaveAgainstAStaleVersionIsRefused(@TempDir Path dir) {
        FileVectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        VectorStoreTemplate template = new VectorStoreTemplate("knowledge", "memory", Map.of(),
                "embeddings", null, Map.of());

        VectorStoreTemplate first = registry.saveTemplate(template.withVersion(0L));
        assertThat(first.version()).isEqualTo(1L);
        VectorStoreTemplate second = registry.saveTemplate(first.withVersion(1L));
        assertThat(second.version()).isEqualTo(2L);

        assertThatThrownBy(() -> registry.saveTemplate(first))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> registry.saveTemplate(template.withVersion(0L)))
                .as("a new template does not overwrite one of the same name")
                .isInstanceOf(StaleVersionException.class);
        assertThat(registry.template("knowledge")).map(VectorStoreTemplate::version).contains(2L);

        assertThat(registry.saveTemplate(template).version()).as("no version: no check").isEqualTo(3L);
    }
}
