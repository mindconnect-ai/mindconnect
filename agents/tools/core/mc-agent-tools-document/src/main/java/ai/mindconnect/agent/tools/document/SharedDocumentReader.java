package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tools.document.DocumentReader;

/**
 * Module-local singleton {@link DocumentReader}. Shared by the three
 * document-reading tool factories so they hit the same parsed-document cache
 * (cache is keyed by file modification time, so concurrent calls are safe).
 */
final class SharedDocumentReader {
    private SharedDocumentReader() {}
    static final DocumentReader INSTANCE = new DocumentReader();
}
