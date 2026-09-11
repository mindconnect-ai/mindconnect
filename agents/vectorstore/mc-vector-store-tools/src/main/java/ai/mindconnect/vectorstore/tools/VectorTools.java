package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three knowledge tools over {@link VectorStores}. All of them take a
 * {@code store} name — a chat session id, an agent knowledge base, whatever
 * the caller scopes — and speak text only; embedding happens inside.
 *
 * <ul>
 *   <li>{@code vector_upsert} — replaces one file's chunks in a store
 *       (delete + insert, so re-ingestion never leaves stale chunks)</li>
 *   <li>{@code vector_search} — embeds the query, returns the top chunks
 *       with score and provenance</li>
 *   <li>{@code vector_delete_file} — removes one file from a store</li>
 * </ul>
 */
public final class VectorTools {

    /** The name prefix of a chat's upload store: {@code session-<sessionId>}. */
    static final String SESSION_STORE_PREFIX = "session-";

    private VectorTools() {}

    /** Shared bind/availability logic for the three factories. */
    abstract static class BaseFactory implements ToolFactory {
        protected VectorStores stores;

        @Override public String group() { return "knowledge"; }

        @Override public void bind(ToolEnvironment env) {
            this.stores = VectorStores.fromEnvironment(env).orElse(null);
        }

        @Override public boolean isAvailable() { return stores != null; }
    }

    public static final class UpsertFactory extends BaseFactory {
        @Override public String name() { return "vector_upsert"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new UpsertTool(stores, scope);
        }
    }

    public static final class SearchFactory extends BaseFactory {
        @Override public String name() { return "vector_search"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new SearchTool(stores, scope);
        }
    }

    public static final class DeleteFileFactory extends BaseFactory {
        @Override public String name() { return "vector_delete_file"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new DeleteFileTool(stores, scope);
        }
    }

    // ── tools ──────────────────────────────────────────────────────────────

    record UpsertTool(VectorStores stores, ToolCallScope callScope) implements Tool {
        @Override public String name() { return "vector_upsert"; }

        @Override public String description() {
            return "Embeds text chunks and stores them in a vector store, replacing all previous "
                    + "chunks of the same file_id. Chunks are objects with 'text' and an optional "
                    + "'title' (e.g. the section heading). Use vector_search to retrieve them later.";
        }

        @Override public Map<String, Object> parametersSchema() {
            Map<String, Object> chunk = Map.of("type", "object",
                    "properties", Map.of(
                            "text", Map.of("type", "string"),
                            "title", Map.of("type", "string")),
                    "required", List.of("text"));
            return schema(Map.of(
                    "store", Map.of("type", "string", "description", "Vector store name."),
                    "template", Map.of("type", "string", "description",
                            "Template for creating the store if it does not exist yet (default: 'default')."),
                    "scope", Map.of("type", "string", "enum", List.of("global", "session", "agent"),
                            "description", "Lifecycle of a NEWLY created store: global (default), "
                                    + "session (tied to this chat session) or agent."),
                    "file_id", Map.of("type", "string", "description", "Id of the source file/document."),
                    "chunks", Map.of("type", "array", "items", chunk)),
                    List.of("store", "file_id", "chunks"));
        }

        @Override public String execute(Map<String, Object> arguments) {
            String storeName = str(arguments, "store");
            String fileId = str(arguments, "file_id");
            if (storeName == null || fileId == null
                    || !(arguments.get("chunks") instanceof List<?> rawChunks) || rawChunks.isEmpty()) {
                return "Error: 'store', 'file_id' and a non-empty 'chunks' array are required.";
            }
            String denied = refusedStore(stores, storeName, callScope);
            if (denied != null) {
                return denied;
            }
            List<String> texts = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (Object raw : rawChunks) {
                if (!(raw instanceof Map<?, ?> map) || !(map.get("text") instanceof String text)
                        || text.isBlank()) {
                    return "Error: every chunk needs a non-blank 'text' (chunk "
                            + (texts.size() + 1) + " has none).";
                }
                texts.add(text);
                titles.add(map.get("title") instanceof String t ? t : "");
            }
            try {
                List<float[]> vectors = stores.embedFor(storeName, texts);
                List<VectorChunk> chunks = new ArrayList<>(texts.size());
                for (int i = 0; i < texts.size(); i++) {
                    Map<String, String> metadata = titles.get(i).isBlank()
                            ? Map.of("file", fileId)
                            : Map.of("file", fileId, "title", titles.get(i));
                    chunks.add(new VectorChunk(fileId + ":" + i, fileId, i, texts.get(i),
                            metadata, vectors.get(i)));
                }
                String scopeArg = str(arguments, "scope");
                VectorStoreInstance.Scope scope = switch (scopeArg == null ? "global" : scopeArg) {
                    case "session" -> VectorStoreInstance.Scope.SESSION;
                    case "agent" -> VectorStoreInstance.Scope.AGENT;
                    default -> VectorStoreInstance.Scope.GLOBAL;
                };
                String scopeRef = switch (scope) {
                    case SESSION -> callScope != null && callScope.sessionId() != null
                            ? callScope.sessionId().value() : null;
                    case AGENT -> callScope != null && callScope.agentId() != null
                            ? callScope.agentId().value() : null;
                    case GLOBAL -> null;
                };
                // The chat's own upload store is registered as the chat's whatever
                // scope was asked for — otherwise its owner is never recorded.
                SessionId ownChat = ownChatStore(storeName, callScope);
                if (ownChat != null) {
                    scope = VectorStoreInstance.Scope.SESSION;
                    scopeRef = ownChat.value();
                }
                // A session-scoped store is the chat's user's, like an upload store.
                String owner = scope == VectorStoreInstance.Scope.SESSION
                        && callScope != null && callScope.userId() != null
                        ? callScope.userId().value() : null;
                VectorStore store = stores.open(storeName, str(arguments, "template"), scope, scopeRef, owner);
                store.deleteFile(fileId);   // replace semantics
                store.upsert(chunks);
                return "Stored " + chunks.size() + " chunk(s) for file '" + fileId + "' in store '"
                        + storeName + "' (dimension " + vectors.get(0).length + ").";
            } catch (RuntimeException e) {
                return "Error: vector_upsert failed: " + e.getMessage();
            }
        }
    }

    record SearchTool(VectorStores stores, ToolCallScope callScope) implements Tool {

        @Override public String name() { return "vector_search"; }

        @Override public String description() {
            return "Semantic search over a vector store: embeds the query and returns the most "
                    + "similar stored chunks with their source file and score. "
                    + "USE THIS for any question about a file the user attached to this chat "
                    + "(omit 'store') and for knowledge stores by name. Attached files are not on "
                    + "the filesystem — this is the only way to read them. NOT for files that have "
                    + "a path on disk: those go to file_read or the document tools.";
        }

        @Override public Map<String, Object> parametersSchema() {
            return schema(Map.of(
                    "store", Map.of("type", "string", "description",
                            "Vector store name. Omit to search this chat session's upload store "
                                    + "(files the user attached to the conversation)."),
                    "query", Map.of("type", "string", "description", "What to look for."),
                    "top_k", Map.of("type", "integer", "description", "Max results (default 5).")),
                    List.of("query"));
        }

        @Override public String execute(Map<String, Object> arguments) {
            String storeName = str(arguments, "store");
            String query = str(arguments, "query");
            if (query == null) {
                return "Error: 'query' is required.";
            }
            if (storeName == null) {
                // The chat's store, not the session's own: a sub-agent's session
                // has no uploads — the files were attached to the chat above it.
                SessionId chat = chatSession(callScope);
                if (chat == null) {
                    return "Error: no 'store' given and no chat session to default to.";
                }
                storeName = sessionStoreName(chat);
            }
            String denied = refusedStore(stores, storeName, callScope);
            if (denied != null) {
                return denied;
            }
            int topK = clamp(arguments.get("top_k"), 5, 20);
            try {
                float[] embedded = stores.embedFor(storeName, List.of(query)).get(0);
                List<VectorStore.SearchHit> hits = stores.openWith(stores.settingsFor(storeName))
                        .search(embedded, topK);
                if (hits.isEmpty()) {
                    return "No results in store '" + storeName + "'. It may be empty — ingest "
                            + "documents first (vector_upsert / the file-ingestion workflow).";
                }
                StringBuilder out = new StringBuilder("Top " + hits.size() + " result(s) from '"
                        + storeName + "':\n");
                int rank = 1;
                for (VectorStore.SearchHit hit : hits) {
                    VectorChunk chunk = hit.chunk();
                    String title = chunk.metadata().getOrDefault("title", "");
                    out.append(rank++).append(". [").append(String.format("%.3f", hit.score()))
                            .append("] ").append(chunk.metadata().getOrDefault("file", chunk.fileId()));
                    if (!title.isBlank()) {
                        out.append(" — ").append(title);
                    }
                    out.append('\n').append(truncate(chunk.text())).append("\n\n");
                }
                return out.toString().stripTrailing();
            } catch (RuntimeException e) {
                return "Error: vector_search failed: " + e.getMessage();
            }
        }
    }

    record DeleteFileTool(VectorStores stores, ToolCallScope callScope) implements Tool {
        @Override public String name() { return "vector_delete_file"; }

        @Override public String description() {
            return "Removes all chunks of one file from a vector store.";
        }

        @Override public Map<String, Object> parametersSchema() {
            return schema(Map.of(
                    "store", Map.of("type", "string"),
                    "file_id", Map.of("type", "string")),
                    List.of("store", "file_id"));
        }

        @Override public String execute(Map<String, Object> arguments) {
            String storeName = str(arguments, "store");
            String fileId = str(arguments, "file_id");
            if (storeName == null || fileId == null) {
                return "Error: 'store' and 'file_id' are required.";
            }
            String denied = refusedStore(stores, storeName, callScope);
            if (denied != null) {
                return denied;
            }
            try {
                stores.openWith(stores.settingsFor(storeName)).deleteFile(fileId);
                return "Removed file '" + fileId + "' from store '" + storeName + "'.";
            } catch (RuntimeException e) {
                return "Error: vector_delete_file failed: " + e.getMessage();
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** The upload store of a chat session — where attached files land. */
    static String sessionStoreName(SessionId sessionId) {
        return SESSION_STORE_PREFIX + sessionId.value();
    }

    /** The chat a call belongs to: the top of its sub-agent chain, where the user's uploads are. */
    static SessionId chatSession(ToolCallScope scope) {
        if (scope == null) {
            return null;
        }
        return scope.rootSessionId() != null ? scope.rootSessionId() : scope.sessionId();
    }

    /**
     * A chat's upload store holds what one user attached to one chat, so it is
     * that user's: a tool reaches it only when it runs for that user — in the
     * chat itself, in another chat of theirs, in a sub-agent the chat started,
     * or in a workflow run on their behalf. Everything else a tool names is a
     * knowledge base (global or an agent's) and stays open.
     *
     * <p>A store is a chat's when it is registered with the {@code SESSION}
     * scope, or when its name has the {@code session-} form the upload
     * pipeline uses. Its user is the owner recorded when the store was
     * registered. Without an owner — a {@code session-} store nobody registered
     * yet, or one from before owners were recorded — only its own chat reaches
     * it; otherwise the model could create {@code session-<other>} itself and
     * read what lands there later.
     *
     * <p>Only a call made in a chat acts for a user. A call outside any chat —
     * a workflow started from the workflow admin or the REST API — reaches no
     * chat's store, whatever user id it carries: such runs resolve their tools
     * as a fixed {@code workflow} user, and a real account may have that name.
     * The upload pipeline runs its ingestion with the chat's scope.
     *
     * @return the tool's error text, or {@code null} when access is fine
     */
    static String refusedStore(VectorStores stores, String storeName, ToolCallScope callScope) {
        VectorStoreInstance registered = stores.registry().instance(storeName).orElse(null);
        boolean chatStore = storeName.startsWith(SESSION_STORE_PREFIX)
                || (registered != null && registered.scope() == VectorStoreInstance.Scope.SESSION);
        if (!chatStore) {
            return null;
        }
        if (registered != null && registered.owner() != null) {
            UserId user = callScope == null || callScope.sessionId() == null ? null : callScope.userId();
            return user != null && registered.owner().equals(user.value()) ? null : refusal(storeName);
        }
        return ownChat(storeName, registered, callScope) ? null : refusal(storeName);
    }

    /** The chat whose own upload store {@code storeName} is — the call's session or its root — or null. */
    static SessionId ownChatStore(String storeName, ToolCallScope scope) {
        if (scope == null) {
            return null;
        }
        for (SessionId session : new SessionId[]{scope.sessionId(), scope.rootSessionId()}) {
            if (session != null && storeName.equals(sessionStoreName(session))) {
                return session;
            }
        }
        return null;
    }

    /** Whether the call runs in the chat the store is named or registered for, or in a sub-agent of it. */
    private static boolean ownChat(String storeName, VectorStoreInstance registered, ToolCallScope scope) {
        if (scope == null) {
            return false;
        }
        for (SessionId session : new SessionId[]{scope.sessionId(), scope.rootSessionId()}) {
            if (session == null) {
                continue;
            }
            if (storeName.equals(sessionStoreName(session))
                    || (registered != null && session.value().equals(registered.scopeRef()))) {
                return true;
            }
        }
        return false;
    }

    private static String refusal(String storeName) {
        return "Error: store '" + storeName + "' holds another chat's uploads and is not accessible "
                + "here. Omit 'store' to search the files attached to this chat.";
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String str(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static int clamp(Object raw, int fallback, int max) {
        try {
            int value = raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
            return Math.max(1, Math.min(value, max));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String text) {
        String stripped = text.strip();
        return stripped.length() > 1500 ? stripped.substring(0, 1500) + "…" : stripped;
    }
}
