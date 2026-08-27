package ai.mindconnect.agent.tools.toolsearch;

import ai.mindconnect.agent.tool.ToolRegistryRef;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.common.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code tool_search} tool: lets an agent discover tools from the live
 * registry instead of carrying every tool definition in its context — the
 * deferred-tools model. Matches are <em>activated</em> for the session
 * ({@link DynamicToolActivations}), so from the next LLM round on they are
 * offered as regular tool definitions with their full schema.
 *
 * <p>Assigning {@code tool_search} to an agent is the operator's grant to
 * roam the registry; the {@code groups} config override narrows the
 * searchable space (e.g. only {@code web} and {@code documents}).
 */
public final class ToolSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchTool.class);
    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_RESULTS = 10;

    private final ToolRegistryRef registryRef;
    private final DynamicToolActivations activations;
    private final Namespace namespace;
    private final UUID sessionId;
    /** The agent's deferred tools — always searchable. */
    private final Set<String> assignedNames;
    /** Registry groups additionally searchable; {@code "*"} = all, empty = none. */
    private final Set<String> allowedGroups;

    public ToolSearchTool(ToolRegistryRef registryRef, DynamicToolActivations activations,
                          Namespace namespace, UUID sessionId,
                          Set<String> assignedNames, Set<String> allowedGroups) {
        this.registryRef = registryRef;
        this.activations = activations;
        this.namespace = namespace;
        this.sessionId = sessionId;
        this.assignedNames = assignedNames;
        this.allowedGroups = allowedGroups;
    }

    @Override
    public String name() {
        return "tool_search";
    }

    @Override
    public String description() {
        // Name what is actually discoverable: a model that only reads
        // "extended toolbox" has no reason to search — one that reads
        // "web_search, code_execute available" does.
        StringBuilder text = new StringBuilder(
                "Searches this agent's extended toolbox by name, description or group. Matching tools "
                + "become available to you as regular tools from your NEXT step on — search first, then "
                + "call the tool you found in the following step. Use this whenever the task needs a "
                + "capability you do not currently have.");
        if (!assignedNames.isEmpty()) {
            text.append(" Hidden tools you can unlock this way: ")
                    .append(String.join(", ", assignedNames)).append('.');
        }
        if (!allowedGroups.isEmpty()) {
            text.append(" Also searchable: the ")
                    .append(allowedGroups.contains("*") ? "entire tool registry"
                            : "registry groups " + String.join(", ", allowedGroups))
                    .append('.');
        }
        return text.toString();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "What you are looking for, e.g. 'read pdf', 'http request', 'run python'.");
        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum number of tools to activate (default " + DEFAULT_RESULTS
                + ", max " + MAX_RESULTS + ").");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", query, "max_results", maxResults));
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object rawQuery = arguments.get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            return "Error: 'query' must be a non-empty string.";
        }
        int limit = clampLimit(arguments.get("max_results"));

        List<Candidate> matches = search(query, limit);
        if (matches.isEmpty()) {
            return "No tools found for '" + query + "'. Searchable: " + searchSpaceSummary() + ".";
        }

        activations.activate(sessionId, matches.stream().map(Candidate::name).toList());

        StringBuilder out = new StringBuilder();
        out.append("Found ").append(matches.size())
                .append(" tool(s) — available to you from your next step on:\n");
        for (Candidate match : matches) {
            out.append("- ").append(match.name()).append(" (").append(match.group()).append("): ")
                    .append(firstLine(match.description())).append('\n');
        }
        return out.toString();
    }

    private record Candidate(String name, String group, String description, int score) {}

    private List<Candidate> search(String query, int limit) {
        String[] tokens = query.toLowerCase(Locale.ROOT).split("\\W+");
        List<Candidate> matches = new ArrayList<>();
        candidates().forEach((toolName, group) -> {
            String description = describe(toolName);
            int score = score(tokens, toolName, group, description);
            if (score > 0) {
                matches.add(new Candidate(toolName, group, description, score));
            }
        });
        matches.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Candidate::name));
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    /**
     * The searchable space, name → group: the agent's deferred tools plus —
     * when a group filter grants it — the registry groups it lists
     * ({@code "*"} opens every group).
     */
    private Map<String, String> candidates() {
        Map<String, Set<String>> byGroup = registryRef.get().toolNamesByGroup();
        Map<String, String> candidates = new LinkedHashMap<>();
        if (!allowedGroups.isEmpty()) {
            boolean all = allowedGroups.contains("*");
            byGroup.forEach((group, names) -> {
                if (all || allowedGroups.contains(group)) {
                    names.forEach(toolName -> candidates.put(toolName, group));
                }
            });
        }
        for (String toolName : assignedNames) {
            candidates.putIfAbsent(toolName, byGroup.entrySet().stream()
                    .filter(e -> e.getValue().contains(toolName))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("assigned"));
        }
        candidates.remove(name());   // searching for the search tool helps nobody
        return candidates;
    }

    /** Resolves the tool briefly just to read its description; "" when unresolvable. */
    private String describe(String toolName) {
        try {
            return registryRef.get()
                    .resolve(AgentTool.of(UUID.randomUUID(), toolName), namespace, null, null)
                    .map(Tool::description)
                    .orElse("");
        } catch (RuntimeException e) {
            log.debug("tool_search could not resolve '{}': {}", toolName, e.getMessage());
            return "";
        }
    }

    private static int score(String[] tokens, String name, String group, String description) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerGroup = group.toLowerCase(Locale.ROOT);
        String lowerDescription = description.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (lowerName.contains(token)) {
                score += 3;
            }
            if (lowerGroup.contains(token)) {
                score += 2;
            }
            if (lowerDescription.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    /** For the no-match message: what the agent is allowed to search at all. */
    private String searchSpaceSummary() {
        List<String> parts = new ArrayList<>();
        if (!assignedNames.isEmpty()) {
            parts.add(assignedNames.size() + " assigned tool(s)");
        }
        if (allowedGroups.contains("*")) {
            parts.add("all groups (" + String.join(", ", registryRef.get().toolNamesByGroup().keySet()) + ")");
        } else if (!allowedGroups.isEmpty()) {
            parts.add("groups: " + String.join(", ", allowedGroups));
        }
        return parts.isEmpty() ? "nothing (no deferred tools, no group filter)" : String.join(" + ", parts);
    }

    private static int clampLimit(Object raw) {
        try {
            int limit = raw == null ? DEFAULT_RESULTS : Integer.parseInt(String.valueOf(raw));
            return Math.max(1, Math.min(limit, MAX_RESULTS));
        } catch (NumberFormatException e) {
            return DEFAULT_RESULTS;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(no description)";
        }
        String line = text.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).strip();
        }
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
