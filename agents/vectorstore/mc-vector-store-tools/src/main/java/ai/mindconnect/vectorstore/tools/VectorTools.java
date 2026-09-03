package ai.mindconnect.vectorstore.tools;

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
            return new DeleteFileTool(stores);
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
                            ? callScope.sessionId().toString() : null;
                    case AGENT -> callScope != null && callScope.agentDefinitionId() != null
                            ? callScope.agentDefinitionId().toString() : null;
                    case GLOBAL -> null;
                };
                VectorStore store = stores.open(storeName, str(arguments, "template"), scope, scopeRef);
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

        /** The chat session's upload store — where attached files land. */
        static String sessionStoreName(java.util.UUID sessionId) {
            return "session-" + sessionId;
        }

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
                if (callScope == null || callScope.sessionId() == null) {
                    return "Error: no 'store' given and no chat session to default to.";
                }
                storeName = sessionStoreName(callScope.sessionId());
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

    record DeleteFileTool(VectorStores stores) implements Tool {
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
            try {
                stores.openWith(stores.settingsFor(storeName)).deleteFile(fileId);
                return "Removed file '" + fileId + "' from store '" + storeName + "'.";
            } catch (RuntimeException e) {
                return "Error: vector_delete_file failed: " + e.getMessage();
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

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
