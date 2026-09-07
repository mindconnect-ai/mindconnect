package ai.mindconnect.agent.protocol.runtime;

import java.util.UUID;

/**
 * Attaches an already-stored file to a session — in the Mindconnect runtime:
 * record it on the session, and for a document chunk + embed it into the
 * session's vector store and activate {@code vector_search}; an image is
 * recorded only, with {@code view_attachment} activated so the model can ask
 * for it again. Supplied by the composition root (e.g.
 * {@code AgentRuntime::attachStored} from the builder); the backend itself
 * neither knows nor cares how "attaching" works.
 *
 * <p>This is the backend-detail seam of concept 9's file story: OpenAI stuffs
 * the referenced document into context, the runtime ingests it for retrieval —
 * same {@code Document(FileId)} item, two strategies.
 */
public interface FileAttacher {

    /** Attaches {@code file} to {@code sessionId}; returns a human-readable status. */
    String attach(UUID sessionId, ai.mindconnect.filestore.StoredFile file);
}
