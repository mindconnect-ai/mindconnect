package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.vectorstore.tools.VectorStores;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in template is the {@code mindconnect.vector-store.*} properties,
 * not a file: saving under its name, or under none, is refused with a
 * reason instead of quietly doing nothing.
 */
class VectorStoreUiControllerTemplatesTest {

    @Test
    void theBuiltInNameAndNoNameAreRefusedWithAReason() {
        assertThat(VectorStoreUiController.saveRefusal(VectorStores.DEFAULT_TEMPLATE))
                .contains("built-in template")
                .contains("mindconnect.vector-store.embedding-config")
                .endsWith("nothing was saved.");
        assertThat(VectorStoreUiController.saveRefusal(" " + VectorStores.DEFAULT_TEMPLATE + " "))
                .as("surrounding blanks do not slip past").contains("built-in template");
        assertThat(VectorStoreUiController.saveRefusal(null)).isEqualTo("The template needs a name — nothing was saved.");
        assertThat(VectorStoreUiController.saveRefusal("   ")).isEqualTo("The template needs a name — nothing was saved.");
        assertThat(VectorStoreUiController.saveRefusal("chat-uploads")).isNull();
    }
}
