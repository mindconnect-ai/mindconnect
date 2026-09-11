package ai.mindconnect.filerepo;

import java.nio.file.Path;

/** {@link Documents#create} found a document under the key already. */
public class DocumentExistsException extends FileRepoException {

    private final transient Path file;

    public DocumentExistsException(Path file) {
        super("Document already exists: " + file);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
