package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code vector_ingest_file}: path in, searchable store content out — the
 * one-call ingestion for workflows ({@code glob → ForEach → vector_ingest_file}).
 * Reads the file relative to the tools base directory, extracts text (via the
 * document reader when {@code mc-agent-tools-document} is on the classpath —
 * docx/pdf/markdown — else plain UTF-8), chunks OpenAI-style (800/400) and
 * embeds into the store, replacing previous chunks of the same path.
 */
public final class VectorIngestFileTool implements Tool {

    private final VectorStores stores;
    private final String baseDir;

    VectorIngestFileTool(VectorStores stores, String baseDir) {
        this.stores = stores;
        this.baseDir = baseDir == null || baseDir.isBlank()
                ? System.getProperty("user.home") : baseDir;
    }

    @Override
    public String name() {
        return "vector_ingest_file";
    }

    @Override
    public String description() {
        return "Reads a file (Word, PDF, markdown, text) and ingests its content into a vector "
                + "store: extract, chunk, embed, store — replacing previous chunks of the same "
                + "path. Use vector_search to query it afterwards.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of("type", "string", "format", "path",
                        "description", "File path relative to the tools base directory."),
                "store", Map.of("type", "string", "description", "Vector store name."),
                "template", Map.of("type", "string", "description",
                        "Template for creating the store if it does not exist (default: 'default').")));
        schema.put("required", List.of("path", "store"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String relative = arguments.get("path") instanceof String p && !p.isBlank() ? p : null;
        String storeName = arguments.get("store") instanceof String s && !s.isBlank() ? s : null;
        if (relative == null || storeName == null) {
            return "Error: 'path' and 'store' are required.";
        }
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base)) {
            return "Error: path escapes the base directory.";
        }
        if (!Files.isRegularFile(file)) {
            return "Error: no such file: " + relative;
        }
        try {
            String text = extractText(base, file);
            String template = arguments.get("template") instanceof String t && !t.isBlank() ? t : null;
            var store = stores.open(storeName, template, VectorStoreInstance.Scope.GLOBAL, null);
            return DirectIngestion.ingest(stores, store, storeName, relative, text);
        } catch (Exception e) {
            return "Error: vector_ingest_file failed for '" + relative + "': " + e.getMessage();
        }
    }

    /**
     * Document extraction when the document module is present (sections keep
     * their headings as context); plain UTF-8 otherwise.
     */
    private static String extractText(Path base, Path file) throws Exception {
        try {
            Class.forName("ai.mindconnect.agent.tools.document.DocumentReader");
            return DocumentExtraction.sectionsAsText(base, file);
        } catch (ClassNotFoundException e) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
    }

    /** Isolated so the optional document types link only when present. */
    private static final class DocumentExtraction {
        static String sectionsAsText(Path base, Path file) throws Exception {
            var model = new ai.mindconnect.agent.tools.document.DocumentReader()
                    .load(base, file);
            StringBuilder text = new StringBuilder();
            for (var section : model.sections()) {
                if (section.title() != null && !section.title().isBlank()) {
                    text.append(section.title()).append('\n');
                }
                text.append(section.content()).append("\n\n");
            }
            return text.toString();
        }
    }

    /** Factory — same availability rules as the other knowledge tools. */
    public static final class Factory extends VectorTools.BaseFactory {
        private String baseDir;

        @Override public String name() { return "vector_ingest_file"; }

        @Override public void bind(ToolEnvironment env) {
            super.bind(env);
            this.baseDir = env.getString("defaultBaseDir").orElse(null);
        }

        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new VectorIngestFileTool(stores, baseDir);
        }
    }
}
