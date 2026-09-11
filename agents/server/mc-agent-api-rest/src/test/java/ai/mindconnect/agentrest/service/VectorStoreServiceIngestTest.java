package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import ai.mindconnect.vectorstore.tools.VectorStores;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ingesting a stored file reads it as the caller: a file the caller may not
 * read is reported like a missing one, before any store is touched.
 */
class VectorStoreServiceIngestTest {

    @TempDir
    Path dir;

    @Test
    void aFileTheReaderMayNotReadIsReportedLikeAMissingOne() throws Exception {
        FileStore files = new FilesystemFileStore(dir.resolve("data"), new Namespace("test"));
        StoredFile alices = files.save("notes.txt", "text/plain",
                new ByteArrayInputStream("secret".getBytes(StandardCharsets.UTF_8)), UserId.of("alice"));
        var beans = new StaticListableBeanFactory();
        beans.addBean("files", files);
        VectorStoreService service = new VectorStoreService(beans.getBeanProvider(VectorStores.class),
                beans.getBeanProvider(FileStore.class), beans.getBeanProvider(WorkflowDataRepository.class),
                beans.getBeanProvider(WorkflowInstanceRepository.class), dir.resolve("tools").toString());

        assertThatThrownBy(() -> service.ingestStoredFile("handbook", alices.id().value(), UserId.of("bob")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such file");
        // Alice's own file passes the check and goes on to the stores — which this host has none of.
        assertThatThrownBy(() -> service.ingestStoredFile("handbook", alices.id().value(), UserId.of("alice")))
                .isInstanceOf(NotConfiguredException.class);
    }
}
