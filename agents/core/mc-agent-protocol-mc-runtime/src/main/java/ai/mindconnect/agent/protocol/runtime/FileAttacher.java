package ai.mindconnect.agent.protocol.runtime;

import java.util.UUID;

/**
 * Ingests an already-stored file into a session's context — in the Mindconnect
 * runtime typically: chunk + embed into the session's vector store and
 * activate the {@code vector_search} tool. Supplied by the composition root
 * (e.g. {@code AgentRuntime::attachStored} from the builder); the backend
 * itself neither knows nor cares how "attaching" works.
 *
 * <p>This is the backend-detail seam of concept 9's file story: OpenAI stuffs
 * the referenced document into context, the runtime ingests it for retrieval —
 * same {@code Document(FileId)} item, two strategies.
 */
public interface FileAttacher {

    /** Ingests {@code file} for {@code sessionId}; returns a human-readable status. */
    String attach(UUID sessionId, ai.mindconnect.filestore.StoredFile file);
}
