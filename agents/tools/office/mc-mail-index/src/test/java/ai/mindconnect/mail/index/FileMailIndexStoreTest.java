package ai.mindconnect.mail.index;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import ai.mindconnect.jackson.JsonMapperFactory;

/** The windows as a file each. */
class FileMailIndexStoreTest extends MailIndexStoreContract {

    @TempDir
    Path dir;

    @Override
    protected MailIndexStore store() {
        return new FileMailIndexStore(dir, "local", new JsonMapperFactory(List.of()));
    }
}
