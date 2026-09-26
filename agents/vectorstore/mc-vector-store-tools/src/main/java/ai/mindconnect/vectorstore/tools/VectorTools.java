package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.EmbeddingHit;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import ai.mindconnect.vectorstore.embedding.MetadataFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * The three knowledge tools over {@link VectorStores}. All of them take a
 * {@code store} name — a chat session id, an agent knowledge base, whatever
 * the caller scopes — and speak text only; embedding happens inside.
 *
 * <ul>
 *   <li>{@code vector_upsert} — embeds one file's or document's chunks and
 *       lists it in a store, replacing what it had (unless the text is the
 *       same, which is not embedded again)</li>
 *   <li>{@code vector_search} — embeds the query, returns the top chunks of
 *       the store's entities with score and provenance, optionally among some
 *       entries only and by metadata</li>
 *   <li>{@code vector_delete_file} — takes one file or document off a store</li>
 * </ul>
 *
 * <p>A store only lists entities; their chunks live once in the namespace's
 * embedding index. A {@code file_id} of type {@code file} is a stored file's
 * id — attached to three chats it is embedded once; of type {@code document}
 * (the default) it names text that belongs to this store alone.
 */
public final class VectorTools {

    /** The name prefix of a chat's upload store: {@code session-<sessionId>}. */
    static final String SESSION_STORE_PREFIX = "session-";

    private VectorTools() {}

    /** Shared bind/availability logic for the three factories. */
    abstract static class BaseFactory implements ToolFactory {
        protected VectorStores stores;
        /** Where a tool created now works: the host's scope, else a fixed namespace, else the default. */
        protected java.util.function.Supplier<Namespace> namespace = () -> Namespace.DEFAULT;

        @Override public String group() { return "knowledge"; }

        @Override public void bind(ToolEnvironment env) {
            this.stores = VectorStores.fromEnvironment(env).orElse(null);
            this.namespace = env.get(ai.mindconnect.agent.ScopeSupplier.class)
                    .<java.util.function.Supplier<Namespace>>map(scope -> scope::namespace)
                    .or(() -> env.get(Namespace.class).map(fixed -> () -> fixed))
                    .orElse(() -> Namespace.DEFAULT);
        }

        @Override public boolean isAvailable() { return stores != null; }
    }

    public static final class UpsertFactory extends BaseFactory {
        @Override public String name() { return "vector_upsert"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new UpsertTool(stores, namespace.get(), scope);
        }
    }

    public static final class SearchFactory extends BaseFactory {
        @Override public String name() { return "vector_search"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new SearchTool(stores, namespace.get(), scope);
        }
    }

    public static final class DeleteFileFactory extends BaseFactory {
        @Override public String name() { return "vector_delete_file"; }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new DeleteFileTool(stores, namespace.get(), scope);
        }
    }

    // ── tools ──────────────────────────────────────────────────────────────

    record UpsertTool(VectorStores stores, Namespace namespace, ToolCallScope callScope) implements Tool {
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
                    "name", Map.of("type", "string", "description",
                            "How hits name the source — a stored file's name, say. Defaults to file_id."),
                    "type", Map.of("type", "string", "enum", List.of("document", "file"), "description",
                            "What file_id names: 'file' for a stored file's id (file-…), shared with every "
                                    + "store that lists it; 'document' (default) for text of this store alone."),
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
            String denied = refusedStore(stores, namespace, storeName, callScope);
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
                String name = str(arguments, "name") == null ? fileId : str(arguments, "name");
                List<VectorStore.TextChunk> chunks = new ArrayList<>(texts.size());
                for (int i = 0; i < texts.size(); i++) {
                    Map<String, String> metadata = titles.get(i).isBlank()
                            ? Map.of("file", name)
                            : Map.of("file", name, "title", titles.get(i));
                    chunks.add(new VectorStore.TextChunk(texts.get(i), metadata));
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
                VectorStore store = stores.open(namespace, storeName, str(arguments, "template"), scope, scopeRef, owner);
                EntityRef ref = "file".equals(str(arguments, "type"))
                        ? EntityRef.of(EntityType.FILE, EntityRef.FILE_STORE, fileId)
                        : store.documentRef(fileId);
                String storeOwner = store.settings().owner();
                long stored = store.put(ref, storeOwner == null ? null : UserId.of(storeOwner), version(chunks), chunks);
                return "Stored " + stored + " chunk(s) for file '" + fileId + "' in store '" + storeName + "'.";
            } catch (RuntimeException e) {
                return "Error: vector_upsert failed: " + e.getMessage();
            }
        }
    }

    record SearchTool(VectorStores stores, Namespace namespace, ToolCallScope callScope) implements Tool {

        @Override public String name() { return "vector_search"; }

        @Override public String description() {
            return "Semantic search over a vector store: embeds the query and returns the most "
                    + "similar stored chunks with their source file and score. "
                    + "USE THIS to find a passage in a long document, across several files at once, "
                    + "or in a knowledge store by name; omit 'store' for the files the user attached "
                    + "to this chat. An attached file the prompt names with a path is on the "
                    + "filesystem as well: file_read and the document tools open it, and that is the "
                    + "better way when the exact content, the structure or a whole short file is what "
                    + "the question needs. Only a file without a path can be read by search alone.";
        }

        @Override public Map<String, Object> parametersSchema() {
            return schema(Map.of(
                    "store", Map.of("type", "string", "description",
                            "Vector store name. Omit to search this chat session's upload store "
                                    + "(files the user attached to the conversation)."),
                    "query", Map.of("type", "string", "description", "What to look for."),
                    "top_k", Map.of("type", "integer", "description", "Max results (default 5)."),
                    "entities", Map.of("type", "array", "items", Map.of("type", "object",
                                    "properties", Map.of(
                                            "id", Map.of("type", "string"),
                                            "type", Map.of("type", "string", "description",
                                                    "file, document, mail, calendar-event, todo, …"),
                                            "source", Map.of("type", "string"),
                                            "container", Map.of("type", "string")),
                                    "required", List.of("id")),
                            "description", "Search only these entries of the store — files, documents, mail, … "
                                    + "— as a hit names them: the id, plus type, source or container where the id "
                                    + "alone is ambiguous. Omit for all."),
                    "where", Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
                            "description", "Only chunks whose metadata has these values, e.g. {\"title\": \"Pricing\"}.")),
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
            String denied = refusedStore(stores, namespace, storeName, callScope);
            if (denied != null) {
                return denied;
            }
            int topK = clamp(arguments.get("top_k"), 5, 20);
            try {
                VectorStore store = stores.store(namespace, storeName);
                Set<EntityRef> within = null;
                if (arguments.get("entities") instanceof List<?> entities && !entities.isEmpty()) {
                    within = store.select(entities.stream().map(EntitySelector::of).toList());
                }
                List<MetadataFilter> filters = new ArrayList<>();
                if (arguments.get("where") instanceof Map<?, ?> where) {
                    where.forEach((k, v) -> filters.add(new MetadataFilter(String.valueOf(k), MetadataFilter.Op.EQ,
                            List.of(String.valueOf(v)))));
                }
                List<EmbeddingHit> hits = store.search(query, topK, within, filters);
                if (hits.isEmpty()) {
                    return "No results in store '" + storeName + "'. It may be empty — ingest "
                            + "documents first (vector_upsert / the file-ingestion workflow).";
                }
                StringBuilder out = new StringBuilder("Top " + hits.size() + " result(s) from '"
                        + storeName + "':\n");
                int rank = 1;
                for (EmbeddingHit hit : hits) {
                    String title = hit.chunk().metadata().getOrDefault("title", "");
                    out.append(rank++).append(". [").append(String.format("%.3f", hit.score()))
                            .append("] ").append(hit.chunk().metadata().getOrDefault("file", hit.ref().id()));
                    if (!title.isBlank()) {
                        out.append(" — ").append(title);
                    }
                    out.append(" (").append(EntitySelector.describe(hit.ref())).append(')');
                    out.append('\n').append(truncate(hit.chunk().text())).append("\n\n");
                }
                return out.toString().stripTrailing();
            } catch (RuntimeException e) {
                return "Error: vector_search failed: " + e.getMessage();
            }
        }
    }

    record DeleteFileTool(VectorStores stores, Namespace namespace, ToolCallScope callScope) implements Tool {
        @Override public String name() { return "vector_delete_file"; }

        @Override public String description() {
            return "Takes one file or document off a vector store. A stored file stays searchable "
                    + "in the other stores and chats that list it.";
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
            String denied = refusedStore(stores, namespace, storeName, callScope);
            if (denied != null) {
                return denied;
            }
            try {
                VectorStore store = stores.store(namespace, storeName);
                int removed = 0;
                for (EntityRef member : store.members()) {
                    if (member.id().equals(fileId)) {
                        store.remove(member);
                        removed++;
                    }
                }
                return removed == 0 ? "Store '" + storeName + "' does not list '" + fileId + "'."
                        : "Removed file '" + fileId + "' from store '" + storeName + "'.";
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
    static String refusedStore(VectorStores stores, Namespace namespace, String storeName, ToolCallScope callScope) {
        VectorStoreInstance registered = stores.registry(namespace).instance(storeName).orElse(null);
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

    /** What changes when the text does — the same chunks upserted again are not embedded again. */
    public static String version(List<VectorStore.TextChunk> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (VectorStore.TextChunk chunk : chunks) {
                digest.update(chunk.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(new java.util.TreeMap<>(chunk.metadata()).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
