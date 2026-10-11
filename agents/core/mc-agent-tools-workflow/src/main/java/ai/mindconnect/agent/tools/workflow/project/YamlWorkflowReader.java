package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.tools.workflow.step.AgentCallData;
import ai.mindconnect.agent.tools.workflow.step.SandboxCodeData;
import ai.mindconnect.agent.tools.workflow.step.ToolCallData;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BaseStepContainerData;
import ai.mindconnect.workflow.domain.BaseStepData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a project workflow — {@code .mindconnect/workflows/<name>.yaml} —
 * into a {@link WorkflowData}. The format says what a step does with its first
 * key, and leaves out what the JSON store spells out:
 *
 * <pre>
 * description: Summarises every section of a Word document
 * input:
 *   wordFile: string                    # required
 *   targetDir: {type: string, default: out}
 * result: summary
 * steps:
 *   - tool: document_sections
 *     args: {path: "${wordFile}"}
 *     as: sectionsJson
 *   - code: |
 *       import json
 *       sections = json.loads(sectionsJson)["sections"]
 *   - foreach: sections
 *     item: section
 *     join: "\n\n"
 *     as: summary
 *     steps:
 *       - agent: Summarizer
 *         message: "Summarise: ${section}"
 *   - if: "len(sections) > 10"
 *     then:
 *       - set: {note: "a long document"}
 * </pre>
 *
 * <p>Steps: {@code tool}, {@code agent}, {@code code}, {@code set},
 * {@code if}, {@code foreach}, {@code block}. Every step takes {@code name}
 * (else {@code <type>-<n>}) and {@code as} (the variable its result goes to).
 * An expression is {@code ${var}}, {@code mini: …} or {@code json: …}; a
 * condition without a prefix is MiniScript. {@code code} is Python or Node
 * and runs in the sandbox — see {@link SandboxCodeData}.
 *
 * <p>What would reach past the caller is not part of the format: another
 * workflow ({@code call}), HTTP from the server ({@code http}), a halt
 * nobody can resume from an agent, an agent defined inline.
 */
final class YamlWorkflowReader {

    private static final ObjectMapper YAML = new YAMLMapper(new YAMLFactory());

    /** The step types, by the key that names them. */
    private static final List<String> TYPES = List.of("tool", "agent", "code", "set", "if", "foreach", "block");

    /** Steps of the JSON store a project workflow cannot have, and why. */
    private static final Map<String, String> NOT_HERE = Map.of(
            "call", "calling another workflow",
            "http", "HTTP requests from the server",
            "halt", "halting (it cannot be resumed from an agent)",
            "jump", "jumps",
            "form", "forms");

    /**
     * A {@code lang:} prefix that asks for a script engine other than
     * MiniScript — engines are registered in lower case, so {@code Python: …}
     * is ordinary text. Only checked where an expression is meant: a
     * {@code set} value and an {@code if} condition.
     */
    private static final Pattern FOREIGN_SCRIPT = Pattern.compile(
            "^\\s*(javascript|js|graal\\.js|ecmascript|nashorn|groovy|python|jython|beanshell|bsh)\\s*:");

    private static final Set<String> CONDITION_PREFIXES = Set.of("mini:", "json:");

    /** A {@code file:} a code step names is read from beside the workflow; past this it is not code. */
    static final int MAX_CODE_FILE = 200_000;

    private final Path baseDir;
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    private YamlWorkflowReader(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** A workflow and what it says about itself. */
    record Read(WorkflowData workflow, String description) {}

    /** Reads {@code file}; {@code name} is the workflow's id. */
    static Read read(String name, Path file) throws IOException {
        return parse(name, Files.readString(file), file.toAbsolutePath().getParent());
    }

    /**
     * Parses one workflow. {@code baseDir} is where {@code code: {file: …}}
     * looks; {@code null} when there is no file to look beside.
     */
    static Read parse(String name, String yaml, Path baseDir) throws IOException {
        Object root = YAML.readValue(yaml, Object.class);
        if (!(root instanceof Map<?, ?> map)) {
            throw new WorkflowFormatException("a workflow is a mapping with 'steps'");
        }
        return new YamlWorkflowReader(baseDir).workflow(name, map);
    }

    private Read workflow(String id, Map<?, ?> map) {
        knownKeys("the workflow", map, Set.of("name", "description", "input", "result", "steps"));
        WorkflowData wf = new WorkflowData();
        wf.setName(id);
        wf.setParams(inputs(map.get("input")));
        String description = text(map.get("description"));
        if (description != null) {
            wf.getParams().description(description);
        }
        wf.setResultFrom(text(map.get("result")));
        wf.setSteps(steps("the workflow", map.get("steps")));
        if (wf.getSteps().isEmpty()) {
            throw new WorkflowFormatException("the workflow has no steps");
        }
        return new Read(wf, description);
    }

    // -----------------------------------------------------------------------
    // Inputs
    // -----------------------------------------------------------------------

    /**
     * {@code name: type} or {@code name: {type, description, default, …}}. An
     * input is required unless it has a default or says {@code required: false}.
     */
    private static Schema inputs(Object raw) {
        if (raw == null) return Schema.object();
        if (!(raw instanceof Map<?, ?> inputs)) {
            throw new WorkflowFormatException("'input' maps each input's name to its type");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        inputs.forEach((key, value) -> {
            String name = String.valueOf(key);
            Map<String, Object> property = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> spec) {
                spec.forEach((k, v) -> property.put(String.valueOf(k), v));
            } else if (value != null) {
                property.put("type", String.valueOf(value));
            }
            property.putIfAbsent("type", "string");
            Object requiredFlag = property.remove("required");
            boolean isRequired = requiredFlag == null ? !property.containsKey("default")
                    : Boolean.parseBoolean(String.valueOf(requiredFlag));
            if (isRequired) required.add(name);
            properties.put(name, property);
        });
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return Schema.fromMap(schema);
    }

    // -----------------------------------------------------------------------
    // Steps
    // -----------------------------------------------------------------------

    private List<StepData> steps(String where, Object raw) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            throw new WorkflowFormatException("'steps' of " + where + " is a list");
        }
        List<StepData> steps = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> step)) {
                throw new WorkflowFormatException("a step in " + where + " is a mapping, not '" + item + "'");
            }
            steps.add(step(step));
        }
        return steps;
    }

    private StepData step(Map<?, ?> map) {
        Set<String> types = new LinkedHashSet<>();
        for (Object key : map.keySet()) {
            String k = String.valueOf(key);
            if (TYPES.contains(k)) types.add(k);
            if (NOT_HERE.containsKey(k)) {
                throw new WorkflowFormatException("a project workflow cannot use '" + k + "' ("
                        + NOT_HERE.get(k) + ")");
            }
        }
        if (types.size() != 1) {
            throw new WorkflowFormatException("a step names exactly one of " + TYPES + ", this one "
                    + (types.isEmpty() ? "none: " + map.keySet() : "several: " + types));
        }
        String type = types.iterator().next();
        String name = text(map.get("name"));
        if (name == null) name = type + "-" + counters.merge(type, 1, Integer::sum);
        String where = "step '" + name + "'";
        BaseStepData step = switch (type) {
            case "tool" -> tool(where, map);
            case "agent" -> agent(where, map);
            case "code" -> code(where, map);
            case "set" -> set(where, map);
            case "if" -> ifStep(where, map);
            case "foreach" -> forEach(where, map);
            case "block" -> block(where, map);
            default -> throw new IllegalStateException(type);
        };
        step.setName(name);
        step.setAssignResultToVar(text(map.get("as")));
        return step;
    }

    private ToolCallData tool(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("tool", "name", "as", "args", "failOnError"));
        ToolCallData step = new ToolCallData();
        step.setTool(required(where, "tool", map));
        Object args = map.get("args");
        if (args instanceof Map<?, ?> values) {
            // Each value resolves on its own: a variable holding quotes or
            // line breaks would break JSON it were spliced into.
            Map<String, Object> copy = new LinkedHashMap<>();
            values.forEach((k, v) -> copy.put(String.valueOf(k), v));
            step.setArgumentValues(copy);
        } else if (args != null) {
            step.setArguments(String.valueOf(args));
        }
        if (map.containsKey("failOnError")) {
            step.setFailOnError(Boolean.parseBoolean(String.valueOf(map.get("failOnError"))));
        }
        return step;
    }

    private AgentCallData agent(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("agent", "name", "as", "message"));
        AgentCallData step = new AgentCallData();
        step.setAgent(required(where, "agent", map));
        step.setMessage(required(where, "message", map));
        return step;
    }

    /** {@code code: <program>} with {@code language}, or {@code code: {file: x.py}} beside the workflow. */
    private SandboxCodeData code(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("code", "name", "as", "language"));
        Object raw = map.get("code");
        String language = text(map.get("language"));
        String code;
        if (raw instanceof Map<?, ?> ref) {
            knownKeys(where + "'s code", ref, Set.of("file"));
            String file = text(ref.get("file"));
            if (file == null) throw new WorkflowFormatException(where + ": 'code' names a 'file'");
            code = codeFile(where, file);
            if (language == null) language = languageOf(file);
        } else {
            code = text(raw);
            if (code == null) throw new WorkflowFormatException(where + " has no code");
        }
        SandboxCodeData step = new SandboxCodeData();
        step.setLanguage(language(where, language == null ? "python" : language));
        step.setCode(code);
        return step;
    }

    private String codeFile(String where, String file) {
        if (baseDir == null) {
            throw new WorkflowFormatException(where + ": 'file' needs the workflow in a directory");
        }
        try {
            // The real path: a symlink beside the workflow must not read a file elsewhere.
            Path path = baseDir.resolve(file).toRealPath();
            if (!path.startsWith(baseDir.toRealPath())) {
                throw new WorkflowFormatException(where + ": '" + file + "' is outside the workflow's directory");
            }
            if (Files.size(path) > MAX_CODE_FILE) {
                throw new WorkflowFormatException(where + ": '" + file + "' is too large to be a step's code");
            }
            return Files.readString(path);
        } catch (IOException e) {
            throw new WorkflowFormatException(where + ": '" + file + "' cannot be read (" + e.getMessage() + ")");
        }
    }

    private static String languageOf(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".cjs")) return "node";
        return "python";
    }

    /** What the sandbox runs: python and node, with the names people write for them. */
    private static String language(String where, String language) {
        return switch (language.strip().toLowerCase(Locale.ROOT)) {
            case "python", "python3", "py" -> "python";
            case "node", "nodejs", "javascript", "js" -> "node";
            default -> throw new WorkflowFormatException(where + ": code runs as python or node, not '"
                    + language + "'");
        };
    }

    /**
     * {@code set: {var: value}}. A string is taken as written — a literal, a
     * {@code ${var}}, a {@code mini:} or {@code json:} expression; any other
     * value keeps its type by going in as JSON.
     */
    private AssignVariablesData set(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("set", "name", "as"));
        if (!(map.get("set") instanceof Map<?, ?> values) || values.isEmpty()) {
            throw new WorkflowFormatException(where + ": 'set' maps variables to their values");
        }
        AssignVariablesData step = new AssignVariablesData();
        values.forEach((k, v) -> {
            String value = v instanceof String s ? s : "json: " + json(v);
            checkExpressions(where, value);
            step.getVariableAssignments().add(new VariableAssignment(String.valueOf(k), value));
        });
        return step;
    }

    private IfData ifStep(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("if", "name", "as", "then", "else"));
        String condition = required(where, "if", map);
        checkExpressions(where, condition);
        // Variables are MiniScript's own names, so a bare condition is MiniScript.
        String lower = condition.stripLeading().toLowerCase(Locale.ROOT);
        if (CONDITION_PREFIXES.stream().noneMatch(lower::startsWith)) {
            condition = RestrictedWorkflowContext.MINI + ": " + condition;
        }
        IfData step = new IfData();
        IfData.Condition branch = new IfData.Condition();
        branch.setCondition(condition);
        branch.setThenBlock(blockOf(where + "'s then", map.get("then")));
        step.setConditions(branch);
        if (map.containsKey("else")) {
            step.setElseBlock(blockOf(where + "'s else", map.get("else")));
        }
        return step;
    }

    private ForEachData forEach(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("foreach", "name", "as", "item", "index", "parallel", "join", "result",
                "steps"));
        ForEachData step = new ForEachData();
        step.setLoopOver(required(where, "foreach", map));
        String item = text(map.get("item"));
        step.setRunVar(item == null ? "item" : item);
        step.setIndexVar(text(map.get("index")));
        step.setParallel(Boolean.parseBoolean(String.valueOf(map.get("parallel"))));
        if (map.containsKey("join")) {
            step.setJoinResults(true);
            step.setJoinDelimiter(String.valueOf(map.get("join")));
        }
        step.setResultFrom(text(map.get("result")));
        step.setSteps(steps(where, map.get("steps")));
        return step;
    }

    private BlockData block(String where, Map<?, ?> map) {
        knownKeys(where, map, Set.of("block", "name", "as", "result"));
        BlockData step = blockOf(where, map.get("block"));
        step.setResultFrom(text(map.get("result")));
        return step;
    }

    private BlockData blockOf(String where, Object raw) {
        BlockData block = new BlockData();
        block.setName(where.replace("step '", "").replace("'", ""));
        block.setSteps(steps(where, raw));
        return block;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Rejects {@code javascript: …} and its kind where an expression is meant.
     * The engine would take it as a plain string anyway; this says so while
     * the file is read instead of letting the step do something the author
     * did not mean.
     */
    private static void checkExpressions(String where, String value) {
        if (FOREIGN_SCRIPT.matcher(value).find()) {
            throw new WorkflowFormatException(where + ": '" + value.strip().split(":", 2)[0]
                    + ":' expressions are not available in a project workflow — use 'mini:' or a code step");
        }
    }

    private static void knownKeys(String where, Map<?, ?> map, Set<String> known) {
        for (Object key : map.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                throw new WorkflowFormatException(where + ": unknown key '" + key + "' (known: "
                        + String.join(", ", known) + ")");
            }
        }
    }

    private static String required(String where, String key, Map<?, ?> map) {
        String value = text(map.get(key));
        if (value == null) throw new WorkflowFormatException(where + ": '" + key + "' is missing");
        return value;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (IOException e) {
            throw new WorkflowFormatException("a value cannot be written as JSON: " + e.getMessage());
        }
    }

    /** The file is not a workflow this reader understands; the message says where. */
    static final class WorkflowFormatException extends RuntimeException {
        WorkflowFormatException(String message) {
            super(message);
        }
    }
}
