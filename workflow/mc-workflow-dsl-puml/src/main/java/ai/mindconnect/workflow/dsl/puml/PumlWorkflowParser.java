package ai.mindconnect.workflow.dsl.puml;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.dsl.puml.spi.DslStepBuilderRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses a subset of the PlantUML activity diagram syntax into a
 * {@link WorkflowData}.
 *
 * <h2>Supported Syntax</h2>
 * <pre>
 * {@literal @}startuml workflowName
 *
 * ' comments start with a single quote
 *
 * ' --- Workflow-level metadata (note block before start) ---
 * note right
 *   params: paramA, paramB
 *   resultFrom: someVar
 * end note
 *
 * ' --- Simple action step (semicolon ends the last property line) ---
 * :assign stepName
 * varA = literalValue
 * varB = "${existingVar}"
 * result = varA;
 *
 * ' --- Code step (requires mc-workflow-code on classpath) ---
 * :code calcSquare
 * language = javascript
 * code = base * base
 * result = squared;
 *
 * ' --- HTTP step (requires mc-workflow-http on classpath) ---
 * :http fetchUser
 * url = https://api.example.com/users/${id}
 * method = GET
 * result = userJson;
 *
 * ' --- Halt step ---
 * :halt waitForApproval
 * next = resumeStep;
 *
 * ' --- Jump step ---
 * :jump skipToEnd
 * to = endStep;
 *
 * ' --- Call workflow step ---
 * :call subWfStep
 * workflow = mySubWorkflow
 * userId = "${currentUserId}"
 * result = subResult;
 *
 * ' --- Conditional branching ---
 * if (spel: score >= 90) then (yes)
 *   :assign highTier
 *   tier = gold;
 * else (no)
 *   :assign lowTier
 *   tier = silver;
 * endif
 * note right: resultFrom = tier
 *
 * ' --- ForEach (uses note block for loop metadata, fork for body) ---
 * note right
 *   loopOver: items
 *   runVar: item
 *   indexVar: i
 *   parallel: true
 * end note
 * fork
 *   :assign processItem
 *   processed = "${item}"
 *   result = processed;
 * end fork
 * note right: foreachResultVar = loopResults
 *
 * ' --- Sub-workflow (partition block) ---
 * partition mySubWorkflow {
 *   :assign greet
 *   message = Hello;
 * }
 *
 * {@literal @}enduml
 * </pre>
 *
 * <h2>Key differences from standard PlantUML</h2>
 * <ul>
 *   <li>Action blocks use {@code :type name\nprop = val;} where the semicolon
 *       terminates the last property line (standard PlantUML allows this).</li>
 *   <li>{@code ${var}} expressions must be quoted as {@code "${var}"} inside
 *       action property lines to avoid PlantUML treating them as variables.</li>
 *   <li>Metadata ({@code params}, {@code resultFrom}, etc.) uses {@code note}
 *       blocks / inline notes rather than custom {@code #} directives.</li>
 *   <li>Sub-workflows use {@code partition Name { ... }} blocks.</li>
 *   <li>ForEach metadata (loopOver, runVar, indexVar, parallel) is declared in
 *       a {@code note} block immediately before the {@code fork} block.</li>
 * </ul>
 *
 * <h2>Extensibility</h2>
 * New step types are supported without parser changes via the
 * {@link DslStepBuilderRegistry} SPI.  Any {@code :typeName stepName} block
 * whose type keyword is registered is handled by the corresponding builder.
 */
public class PumlWorkflowParser {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String STARTUML = "@startuml";
    private static final String ENDUML = "@enduml";
    private static final String CODE_FENCE = "---code---";

    private final DslStepBuilderRegistry registry;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Creates a parser with the default (ServiceLoader-populated) registry. */
    public PumlWorkflowParser() {
        this(new DslStepBuilderRegistry());
    }

    /** Creates a parser using the provided registry. */
    public PumlWorkflowParser(DslStepBuilderRegistry registry) {
        this.registry = registry;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Parses the given PlantUML text. */
    public WorkflowData parse(String puml) {
        List<String> lines = tokenizeLines(puml);
        Cursor cursor = new Cursor(lines);

        // Expect @startuml
        String firstLine = cursor.nextNonEmpty();
        if (firstLine == null || !firstLine.toLowerCase().startsWith(STARTUML)) {
            throw new PumlParseException("Expected @startuml, got: " + firstLine);
        }
        String workflowName = firstLine.substring(STARTUML.length()).trim();

        WorkflowData wf = new WorkflowData();
        if (!workflowName.isEmpty()) {
            wf.setName(workflowName);
        }

        parseBody(cursor, wf, false, null);
        return wf;
    }

    /** Parses PlantUML from an InputStream (UTF-8). */
    public WorkflowData parse(InputStream in) throws IOException {
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Parses PlantUML from a file {@link java.nio.file.Path} (UTF-8). */
    public WorkflowData parse(java.nio.file.Path path) throws IOException {
        return parse(java.nio.file.Files.readString(path, StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Body parser (recursive for if/fork/partition)
    // -----------------------------------------------------------------------

    /**
     * Parses steps into {@code target} until a terminating token or EOF.
     *
     * @param cursor        line cursor
     * @param target        container to add parsed steps into
     * @param insideBlock   when true, stop at else/endif/end fork/} etc.
     * @param pendingNote   note metadata accumulated before this block (for fork)
     * @return the terminating line (e.g. "else", "endif") or null
     */
    private String parseBody(Cursor cursor, BaseStepContainerData target,
                             boolean insideBlock, Map<String, String> pendingNote) {

        // Apply any pending note metadata to this container immediately
        if (pendingNote != null) {
            applyNoteMetadata(pendingNote, target);
        }

        while (cursor.hasMore()) {
            String line = cursor.peek();
            if (line == null) break;
            String lower = line.toLowerCase().trim();

            // Stop conditions when inside a block
            if (insideBlock) {
                if (lower.startsWith("else") || lower.equals("endif")
                        || lower.startsWith("end fork") || lower.equals("}")) {
                    cursor.consume();
                    return lower;
                }
            }

            if (lower.startsWith(ENDUML)) {
                cursor.consume();
                return ENDUML;
            }

            // --- :input [->] param1, param2; or :for [->] items as item ...; ---
            if (lower.startsWith(":input ") || lower.startsWith(":for ")) {
                cursor.consume();
                int prefixLen = lower.startsWith(":for ") ? ":for".length() : ":input".length();
                String content = stripArrow(line.trim().substring(prefixLen).trim());
                if (content.endsWith(";")) content = content.substring(0, content.length() - 1).trim();
                // If the very next thing is a fork, treat as forEach config
                String nextLine = cursor.peek();
                if (nextLine != null && nextLine.trim().toLowerCase().startsWith("fork")) {
                    cursor.consume();
                    StepData forEachStep = parseFork(nextLine, cursor, parseForEachInput(content));
                    applyForEachResultVar(cursor, forEachStep);
                    target.addSteps(forEachStep);
                } else {
                    // Treat as workflow params: comma-separated names
                    if (target instanceof WorkflowData wd) {
                        for (String p : content.split(",")) {
                            String t = p.trim();
                            if (!t.isEmpty()) wd.addParam(t);
                        }
                    }
                }
                continue;
            }

            // --- :output [<-] varName; — workflow resultFrom ---
            if (lower.startsWith(":output ")) {
                cursor.consume();
                String content = stripArrow(line.trim().substring(":output".length()).trim());
                if (content.endsWith(";")) content = content.substring(0, content.length() - 1).trim();
                target.setResultFrom(content.trim());
                continue;
            }

            // --- Note block: multi-line metadata (kept for backward compat / forEach config) ---
            if (lower.startsWith("note ") || lower.equals("note")) {
                cursor.consume();
                Map<String, String> noteMeta = parseNoteBlock(line, cursor);
                // If the very next thing is a fork, attach as pending note
                String nextLine = cursor.peek();
                if (nextLine != null && nextLine.trim().toLowerCase().startsWith("fork")) {
                    cursor.consume();
                    StepData forEachStep = parseFork(nextLine, cursor, noteMeta);
                    applyForEachResultVar(cursor, forEachStep);
                    target.addSteps(forEachStep);
                } else {
                    // Apply to current container
                    applyNoteMetadata(noteMeta, target);
                }
                continue;
            }

            // --- Partition block: sub-workflow ---
            if (lower.startsWith("partition ")) {
                cursor.consume();
                // Extract name: partition Name { or partition "Name" {
                String partitionName = line.trim().substring("partition".length()).trim();
                partitionName = partitionName.replaceAll("\\{.*", "").replaceAll("[\"']", "").trim();
                WorkflowData sub = new WorkflowData();
                sub.setName(partitionName);
                // If opening brace is on same line, we've already consumed it; else skip it
                String nextLine = cursor.peek();
                if (nextLine != null && nextLine.trim().equals("{")) {
                    cursor.consume();
                }
                parseBody(cursor, sub, true, null); // stops at "}"
                if (target instanceof WorkflowData wd) {
                    wd.getSubWorkflows().add(sub);
                }
                continue;
            }

            // --- Legacy #-directives (kept for backward compat with old files) ---
            if (lower.startsWith("#params:")) {
                cursor.consume();
                String params = line.substring(line.indexOf(':') + 1).trim();
                for (String p : params.split(",")) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty() && target instanceof WorkflowData wd) {
                        wd.addParam(trimmed);
                    }
                }
                continue;
            }
            if (lower.startsWith("#resultfrom:")) {
                cursor.consume();
                target.setResultFrom(line.substring(line.indexOf(':') + 1).trim());
                continue;
            }
            if (lower.startsWith("#workflowtype:")) {
                cursor.consume();
                if (target instanceof WorkflowData wd) {
                    wd.setWorkflowType(line.substring(line.indexOf(':') + 1).trim());
                }
                continue;
            }
            if (lower.startsWith("#foreachresultvar:")) {
                // Handled inline after fork — skip here
                cursor.consume();
                continue;
            }

            // --- Legacy sub/end sub (kept for backward compat) ---
            if (lower.startsWith("sub ")) {
                cursor.consume();
                String subName = line.trim().substring(4).trim();
                WorkflowData sub = new WorkflowData();
                sub.setName(subName);
                parseBody(cursor, sub, true, null); // stops at "end sub"
                if (target instanceof WorkflowData wd) {
                    wd.getSubWorkflows().add(sub);
                }
                continue;
            }

            // --- Start / stop / end nodes (cosmetic, skip) ---
            if (lower.equals("start") || lower.equals("stop") || lower.equals("end")) {
                cursor.consume();
                continue;
            }

            // --- Conditional: if (...) then ---
            if (lower.startsWith("if ") || lower.startsWith("if(")) {
                cursor.consume();
                StepData ifStep = parseIfStatement(line, cursor);
                target.addSteps(ifStep);
                continue;
            }

            // --- Fork (ForEach) without preceding note ---
            if (lower.startsWith("fork")) {
                cursor.consume();
                StepData forEachStep = parseFork(line, cursor, null);
                applyForEachResultVar(cursor, forEachStep);
                target.addSteps(forEachStep);
                continue;
            }

            // --- Action block: :type name ... ; ---
            if (lower.startsWith(":")) {
                cursor.consume();
                StepData step = parseActionBlock(line, cursor);
                if (step != null) {
                    // A note right immediately after the action carries multiline code/body
                    applyTrailingNote(cursor, step);
                    target.addSteps(step);
                }
                continue;
            }

            // --- Arrow / connector lines (skip cosmetic PlantUML syntax) ---
            if (lower.startsWith("->") || lower.startsWith("-[") || lower.equals("|")) {
                cursor.consume();
                continue;
            }

            // Unknown line — skip
            cursor.consume();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Note block parser
    // -----------------------------------------------------------------------

    /**
     * Parses a note block into a key/value map.
     *
     * Handles both:
     * <ul>
     *   <li>Inline: {@code note right: key = value}</li>
     *   <li>Multi-line: {@code note right\n  key: value\n  ...\nend note}</li>
     * </ul>
     */
    private Map<String, String> parseNoteBlock(String firstLine, Cursor cursor) {
        Map<String, String> meta = new LinkedHashMap<>();

        // Inline note: "note right: key = value" or "note right: key = value"
        int colon = firstLine.indexOf(':');
        if (colon >= 0) {
            // Everything after the first colon is the content
            String content = firstLine.substring(colon + 1).trim();
            parseNoteEntry(content, meta);
            return meta;
        }

        // Multi-line note: read until "end note"
        while (cursor.hasMore()) {
            String line = cursor.peek().trim();
            if (line.equalsIgnoreCase("end note")) {
                cursor.consume();
                break;
            }
            cursor.consume();
            if (!line.isEmpty()) {
                parseNoteEntry(line, meta);
            }
        }
        return meta;
    }

    /** Parses a single {@code key: value} or {@code key = value} entry into the map. */
    private void parseNoteEntry(String line, Map<String, String> meta) {
        // Support both "key: value" and "key = value"
        int sep = line.indexOf(':');
        int eq = line.indexOf('=');
        if (sep >= 0 && (eq < 0 || sep < eq)) {
            meta.put(line.substring(0, sep).trim().toLowerCase(Locale.ROOT),
                    line.substring(sep + 1).trim());
        } else if (eq >= 0) {
            meta.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                    line.substring(eq + 1).trim());
        }
    }

    /**
     * Applies note metadata entries to a container.
     * Recognises: params, resultfrom, workflowtype, loopover, runvar, indexvar,
     * parallel, foreachresultvar.
     */
    private void applyNoteMetadata(Map<String, String> meta, BaseStepContainerData target) {
        for (Map.Entry<String, String> e : meta.entrySet()) {
            switch (e.getKey()) {
                case "params" -> {
                    if (target instanceof WorkflowData wd) {
                        for (String p : e.getValue().split(",")) {
                            String t = p.trim();
                            if (!t.isEmpty()) wd.addParam(t);
                        }
                    }
                }
                case "resultfrom" -> target.setResultFrom(e.getValue());
                case "workflowtype" -> {
                    if (target instanceof WorkflowData wd) wd.setWorkflowType(e.getValue());
                }
                // forEach metadata is applied separately via parseFork
                default -> { }
            }
        }
    }

    /**
     * Strips a leading {@code ->} or {@code <-} arrow token from an input/output content string.
     * e.g. {@code "-> apiBase"} → {@code "apiBase"},  {@code "<- summary"} → {@code "summary"}.
     */
    private String stripArrow(String content) {
        if (content.startsWith("->")) return content.substring(2).trim();
        if (content.startsWith("<-")) return content.substring(2).trim();
        return content;
    }

    /**
     * Looks ahead for a {@code note right ... end note} block immediately after an action step
     * and injects its content as the {@code code} or {@code body} property of the step.
     *
     * <p>The note content is parsed as key/value pairs exactly like inline properties.
     * A bare multi-line block without a key prefix is treated as the {@code code} value.
     */
    private void applyTrailingNote(Cursor cursor, StepData step) {
        if (!cursor.hasMore()) return;
        String peek = cursor.peek().trim().toLowerCase();
        if (!peek.startsWith("note ") && !peek.equals("note")) return;

        cursor.consume();
        List<String> noteLines = new ArrayList<>();
        while (cursor.hasMore()) {
            String line = cursor.peek().trim();
            if (line.equalsIgnoreCase("end note")) {
                cursor.consume();
                break;
            }
            cursor.consume();
            if (!line.isEmpty()) noteLines.add(line);
        }

        if (noteLines.isEmpty()) return;

        // Determine if the note is key/value style or raw code block
        // Key/value style: first non-empty line contains "key:" or "key ="
        // Otherwise treat entire content as code/body depending on step type
        Map<String, String> props = new LinkedHashMap<>();
        String firstLine = noteLines.get(0);
        int sep = firstLine.indexOf(':');
        int eq = firstLine.indexOf('=');
        boolean isKeyValue = (sep > 0 && (eq < 0 || sep < eq))
                || (eq > 0 && firstLine.substring(0, eq).trim().matches("[a-zA-Z][a-zA-Z0-9]*"));

        if (isKeyValue) {
            // Check if this is a "key:\n<multiline body>" pattern: first line is "key:" with no value,
            // and the remaining lines are the actual multiline content.
            int firstSep = firstLine.indexOf(':');
            String firstKey = firstSep > 0 ? firstLine.substring(0, firstSep).trim() : "";
            String firstVal = firstSep > 0 ? firstLine.substring(firstSep + 1).trim() : "";
            if (!firstKey.isEmpty() && firstVal.isEmpty() && noteLines.size() > 1) {
                // e.g.  "code:"  followed by actual code lines
                String rawContent = String.join("\n", noteLines.subList(1, noteLines.size()));
                props.put(firstKey.toLowerCase(Locale.ROOT), rawContent);
            } else {
                // Normal key/value entries
                for (String line : noteLines) {
                    parseNoteEntry(line, props);
                }
            }
        } else {
            // Raw multiline block — infer property name from step type
            String rawContent = String.join("\n", noteLines);
            String propName = inferMultilineProperty(step);
            props.put(propName, rawContent);
        }

        // Apply the parsed properties back to the step via the builder
        applyNotePropsToStep(step, props);
    }

    /** Returns the property name that should receive a raw multiline note block for this step. */
    private String inferMultilineProperty(StepData step) {
        String type = step.getType(); // "code", "http", "assign", etc.
        return switch (type) {
            case "http" -> "body";
            default -> "code";
        };
    }

    /** Applies a property map parsed from a trailing note back onto the step's fields. */
    private void applyNotePropsToStep(StepData step, Map<String, String> props) {
        // Delegate back through the same builder machinery
        String typeName = step.getType();
        String stepName = step.getName();
        // Build a merged props map: existing step state is already set, just overlay note props
        // We re-use the build*() methods but need to be careful not to reset already-set fields.
        // Simplest: directly manipulate known types.
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            String value = e.getValue();
            try {
                applyPropertyToStep(step, key, value);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Applies a single property key/value to a step directly.
     * Uses instanceof checks on the known domain types (HttpCallData, CodeData).
     */
    private void applyPropertyToStep(StepData step, String key, String value) {
        if (step instanceof CodeData code) {
            switch (key) {
                case "code"                    -> code.setCode(value);
                case "language"                -> code.setLanguage(value);
                case "assign-result", "result" -> code.setAssignResultToVar(value);
                default -> { }
            }
        } else if (step instanceof HttpCallData http) {
            switch (key) {
                case "body"                    -> http.setBody(value);
                case "contenttype"             -> http.setContentType(value);
                case "assign-result", "result" -> http.setAssignResultToVar(value);
                default -> { }
            }
        }
    }

    /**
     * Parses the content of {@code :input items as item index i parallel;} into a
     * forEach metadata map used by {@link #parseFork}.
     *
     * <p>Grammar: {@code loopVar [as runVar] [index indexVar] [parallel]}
     */
    private Map<String, String> parseForEachInput(String content) {
        Map<String, String> meta = new LinkedHashMap<>();
        String[] tokens = content.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i].toLowerCase()) {
                case "as" -> { if (i + 1 < tokens.length) meta.put("runvar", tokens[++i]); }
                case "index" -> { if (i + 1 < tokens.length) meta.put("indexvar", tokens[++i]); }
                case "parallel" -> meta.put("parallel", "true");
                default -> {
                    if (i == 0) meta.put("loopover", tokens[i]); // first token is the loopOver var
                }
            }
        }
        return meta;
    }

    // -----------------------------------------------------------------------
    // Action block parser:  :type name\n  key = val\n  lastkey = val; ---
    // -----------------------------------------------------------------------

    private StepData parseActionBlock(String firstLine, Cursor cursor) {
        // Strip leading ':' and trailing ';' if the header itself ends with one
        String header = firstLine.trim().substring(1); // remove ':'
        boolean closedOnHeader = false;
        if (header.endsWith(";")) {
            header = header.substring(0, header.length() - 1).trim();
            closedOnHeader = true;
        }

        // Parse type and name from first line
        String[] parts = header.split("\\s+", 2);
        String typeName = parts[0].trim();
        String stepName = parts.length > 1 ? parts[1].trim() : typeName;

        Map<String, String> props = new LinkedHashMap<>();

        if (!closedOnHeader) {
            collectProperties(cursor, props);
        }

        // Dispatch to registry or built-in handler
        return buildStep(typeName, stepName, props);
    }

    /**
     * Reads {@code key = value} lines until a standalone {@code ;} or a line
     * ending with {@code ;}.  Strips surrounding quotes from values.
     */
    private void collectProperties(Cursor cursor, Map<String, String> props) {
        List<String> codeLines = null;
        boolean inCodeFence = false;

        while (cursor.hasMore()) {
            String raw = cursor.peek();
            String line = raw.trim();

            // Standalone ';' terminates
            if (line.equals(";")) {
                cursor.consume();
                break;
            }

            // Line ending with ';' — last property line
            boolean terminal = line.endsWith(";");
            if (terminal) {
                line = line.substring(0, line.length() - 1).trim();
            }
            cursor.consume();

            if (line.equalsIgnoreCase(CODE_FENCE)) {
                if (!inCodeFence) {
                    inCodeFence = true;
                    codeLines = new ArrayList<>();
                } else {
                    inCodeFence = false;
                    props.put("code", String.join("\n", codeLines));
                    codeLines = null;
                }
                if (terminal) break;
                continue;
            }

            if (inCodeFence) {
                codeLines.add(line);
                if (terminal) break;
                continue;
            }

            int arrow = line.indexOf("->");
            int eq    = line.indexOf('=');
            // Prefer "->" over "=" when "->" appears before "=" (or there is no "=").
            // This lets  "result -> varName"  work alongside  "result = varName".
            if (arrow > 0 && (eq < 0 || arrow < eq)) {
                String key   = line.substring(0, arrow).trim();
                String value = unquote(line.substring(arrow + 2).trim());
                props.put(key, value);
            } else if (eq > 0) {
                String key   = line.substring(0, eq).trim();
                String value = unquote(line.substring(eq + 1).trim());
                props.put(key, value);
            }

            if (terminal) break;
        }
    }

    /**
     * Strips surrounding double-quotes from a value and unescapes inner {@code \"} sequences.
     * e.g. {@code "{\"key\":\"val\"}"} → {@code {"key":"val"}}
     */
    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        return value;
    }

    private StepData buildStep(String typeName, String stepName, Map<String, String> props) {
        // 1. Try registered builder
        StepData built = registry.buildOrNull(typeName, stepName, props);
        if (built != null) return built;

        // 2. Built-in keywords
        return switch (typeName.toLowerCase(Locale.ROOT)) {
            case "halt" -> buildHalt(stepName, props);
            case "jump" -> buildJump(stepName, props);
            case "call" -> buildCall(stepName, props);
            default -> buildGeneric(typeName, stepName, props);
        };
    }

    // -----------------------------------------------------------------------
    // Built-in step builders
    // -----------------------------------------------------------------------

    private HaltData buildHalt(String name, Map<String, String> props) {
        HaltData h = new HaltData();
        h.setName(name);
        for (Map.Entry<String, String> e : props.entrySet()) {
            switch (e.getKey().toLowerCase()) {
                case "condition" -> h.setCondition(e.getValue());
                case "next" -> h.setNext(e.getValue());
                case "returnresult" -> h.setReturnResult(Boolean.parseBoolean(e.getValue()));
                case "returnresultexpression" -> h.setReturnResultExpression(e.getValue());
                case "assign-result", "result" -> h.setAssignResultToVar(e.getValue());
                default -> { }
            }
        }
        return h;
    }

    private JumpToData buildJump(String name, Map<String, String> props) {
        JumpToData j = new JumpToData();
        j.setName(name);
        j.setJumpTo(props.getOrDefault("to", props.get("jumpTo")));
        return j;
    }

    private CallWorkflowData buildCall(String name, Map<String, String> props) {
        CallWorkflowData c = new CallWorkflowData();
        c.setName(name);
        for (Map.Entry<String, String> e : props.entrySet()) {
            switch (e.getKey().toLowerCase()) {
                case "workflow" -> c.setWorkflow(e.getValue());
                case "assign-result", "result" -> c.setAssignResultToVar(e.getValue());
                default -> c.addAssignParam(e.getKey(), e.getValue());
            }
        }
        return c;
    }

    /**
     * Fallback: treat as a generic {@link AssignVariablesData} that captures
     * all properties as variable assignments.
     */
    private StepData buildGeneric(String typeName, String stepName, Map<String, String> props) {
        AssignVariablesData assign = new AssignVariablesData();
        assign.setName(stepName.isEmpty() ? typeName : stepName);
        for (Map.Entry<String, String> e : props.entrySet()) {
            if ("assign-result".equalsIgnoreCase(e.getKey()) || "result".equalsIgnoreCase(e.getKey())) {
                assign.setAssignResultToVar(e.getValue());
            } else {
                assign.getVariableAssignments().add(new VariableAssignment(e.getKey(), e.getValue()));
            }
        }
        return assign;
    }

    // -----------------------------------------------------------------------
    // If / conditional
    // -----------------------------------------------------------------------

    /**
     * Parses:
     * <pre>
     * if (condition) then (yes)
     *   ...steps...
     * else (no)          ← optional
     *   ...steps...
     * endif
     * </pre>
     */
    private StepData parseIfStatement(String ifLine, Cursor cursor) {
        String condition = extractParenContent(ifLine);

        IfData ifData = new IfData();
        ifData.setName(generateName("if"));

        // Parse then-block
        BlockData thenBlock = new BlockData();
        thenBlock.setName(ifData.getName() + "_then");
        String terminator = parseBody(cursor, thenBlock, true, null);

        IfData.Condition cond = new IfData.Condition();
        cond.setCondition(condition);
        cond.setThenBlock(thenBlock);
        ifData.setConditions(cond);

        // Optional else block
        if (terminator != null && terminator.toLowerCase().startsWith("else")) {
            BlockData elseBlock = new BlockData();
            elseBlock.setName(ifData.getName() + "_else");
            parseBody(cursor, elseBlock, true, null); // stops at "endif"
            if (!elseBlock.getSteps().isEmpty()) {
                ifData.setElseBlock(elseBlock);
            }
        }

        return ifData;
    }

    // -----------------------------------------------------------------------
    // Fork (ForEach)
    // -----------------------------------------------------------------------

    /**
     * Parses a fork block into a {@link ForEachData}.
     *
     * <p>Loop metadata (loopOver, runVar, indexVar, parallel) can come from:
     * <ol>
     *   <li>A {@code note} block immediately before the {@code fork} (passed as {@code noteMeta})</li>
     *   <li>Inline tokens on the fork line: {@code fork over items as item index i parallel}</li>
     * </ol>
     */
    private StepData parseFork(String forkLine, Cursor cursor, Map<String, String> noteMeta) {
        ForEachData forEach = new ForEachData();
        forEach.setName(generateName("forEach"));

        // Apply note metadata first (lower priority than inline tokens)
        if (noteMeta != null) {
            for (Map.Entry<String, String> e : noteMeta.entrySet()) {
                switch (e.getKey()) {
                    case "loopover" -> forEach.setLoopOver(e.getValue());
                    case "runvar" -> forEach.setRunVar(e.getValue());
                    case "indexvar" -> forEach.setIndexVar(e.getValue());
                    case "parallel" -> forEach.setParallel(Boolean.parseBoolean(e.getValue()));
                    default -> { }
                }
            }
        }

        // Apply inline fork-line tokens (override note metadata)
        String[] tokens = forkLine.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i].toLowerCase()) {
                case "over" -> { if (i + 1 < tokens.length) forEach.setLoopOver(tokens[++i]); }
                case "as" -> { if (i + 1 < tokens.length) forEach.setRunVar(tokens[++i]); }
                case "index" -> { if (i + 1 < tokens.length) forEach.setIndexVar(tokens[++i]); }
                case "parallel" -> forEach.setParallel(true);
                default -> { }
            }
        }

        // Parse body steps — stop at "end fork" or "fork again" (treat again as another branch, ignored)
        parseForkBody(cursor, forEach);

        return forEach;
    }

    /**
     * Parses the body of a fork block, skipping {@code fork again} separators
     * (parallel branches are merged into one body for ForEach purposes).
     */
    private void parseForkBody(Cursor cursor, ForEachData forEach) {
        while (cursor.hasMore()) {
            String line = cursor.peek();
            if (line == null) break;
            String lower = line.trim().toLowerCase();

            if (lower.startsWith("end fork")) {
                cursor.consume();
                break;
            }
            if (lower.equals("fork again")) {
                cursor.consume(); // skip additional parallel branches
                continue;
            }
            // Parse one step worth of lines
            parseBody(cursor, forEach, true, null);
            break;
        }
    }

    /**
     * Looks ahead for an {@code :output varName;} immediately after a fork (or after
     * an intervening {@code stop} line) to set {@code assignResultToVar} on the forEach.
     */
    private void applyForEachResultVar(Cursor cursor, StepData forEachStep) {
        if (!(forEachStep instanceof ForEachData forEach)) return;
        if (!cursor.hasMore()) return;
        String peek = cursor.peek().trim().toLowerCase();

        // Skip over a "stop" that may appear between end fork and :output
        if (peek.equals("stop")) {
            // Consume stop, then check again
            cursor.consume();
            if (!cursor.hasMore()) return;
            peek = cursor.peek().trim().toLowerCase();
        }

        // Primary: :output results;  or  :output <- results;
        if (peek.startsWith(":output ")) {
            String line = cursor.consume().trim();
            String content = stripArrow(line.substring(":output".length()).trim());
            if (content.endsWith(";")) content = content.substring(0, content.length() - 1).trim();
            forEach.setAssignResultToVar(content);
            return;
        }

        // Handle: note right: foreachresultvar = results
        if (peek.startsWith("note ") && peek.contains("foreachresultvar")) {
            String line = cursor.consume().trim();
            int colon = line.indexOf(':');
            if (colon >= 0) {
                String content = line.substring(colon + 1).trim();
                Map<String, String> m = new LinkedHashMap<>();
                parseNoteEntry(content, m);
                String varName = m.get("foreachresultvar");
                if (varName != null) forEach.setAssignResultToVar(varName);
            }
            return;
        }

        // Legacy: #foreachResultVar: results
        if (peek.startsWith("#foreachresultvar:")) {
            String line = cursor.consume().trim();
            String varName = line.substring(line.indexOf(':') + 1).trim();
            forEach.setAssignResultToVar(varName);
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /** Extracts content from the first pair of parentheses in the line. */
    private String extractParenContent(String line) {
        int open = line.indexOf('(');
        int close = line.indexOf(')');
        if (open >= 0 && close > open) {
            return line.substring(open + 1, close).trim();
        }
        return line.trim();
    }

    private int nameCounter = 0;

    private String generateName(String prefix) {
        return prefix + "_" + (++nameCounter);
    }

    // -----------------------------------------------------------------------
    // Line tokenization
    // -----------------------------------------------------------------------

    /**
     * Splits input into lines, strips PlantUML comment lines ({@code '...}).
     */
    private List<String> tokenizeLines(String puml) {
        List<String> result = new ArrayList<>();
        for (String line : puml.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("'")) continue; // comment
            result.add(trimmed);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Cursor
    // -----------------------------------------------------------------------

    private static class Cursor {
        private final List<String> lines;
        private int pos = 0;

        Cursor(List<String> lines) {
            this.lines = lines;
        }

        boolean hasMore() {
            return pos < lines.size();
        }

        /** Returns the next non-blank line without advancing. */
        String peek() {
            while (pos < lines.size()) {
                String line = lines.get(pos);
                if (!line.isBlank()) return line;
                pos++;
            }
            return null;
        }

        /** Advances past the current non-blank line and returns it. */
        String consume() {
            while (pos < lines.size()) {
                String line = lines.get(pos++);
                if (!line.isBlank()) return line;
            }
            return null;
        }

        /** Returns and consumes the next non-blank line. */
        String nextNonEmpty() {
            return consume();
        }
    }
}
