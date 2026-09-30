package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.workflow.execution.BaseStepInstance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a {@link SandboxCodeData} through the {@code code_execute} tool the
 * run's caller has — never in the server's own process. The program is wrapped
 * so the workflow's variables arrive as its top-level variables and the ones
 * it sets travel back: the wrapper prints them as one JSON line after a
 * marker, and this step reads that line out of the tool's output.
 */
public class SandboxCodeStep extends BaseStepInstance<SandboxCodeData> {

    /** The tool the program runs with. */
    public static final String TOOL = "code_execute";

    /** Precedes the line the wrapper prints the variables on. */
    static final String MARKER = "@@mc-workflow-vars@@";

    /** Never sent: the server's environment has no business in a project's code. */
    private static final String ENV_VARIABLE = "env";

    /** How much of a failed program's output an error message quotes. */
    private static final int ERROR_EXCERPT = 2_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EXIT_CODE = Pattern.compile("^exit code: (-?\\d+)", Pattern.MULTILINE);

    @Override
    public void execute() {
        SandboxCodeData cfg = getConfig();
        String code = cfg.getCode();
        if (code == null || code.isBlank()) {
            setResult(null);
            return;
        }
        String language = cfg.getLanguage() == null ? "python" : cfg.getLanguage();
        CallerLimits limits = getWorkflowContext() == null ? null
                : getWorkflowContext().getAttribute(CallerLimits.class);
        if (limits != null) {
            limits.checkTool(cfg.getName(), TOOL);
        }
        ToolCallScope scope = getWorkflowContext() == null ? null
                : getWorkflowContext().getAttribute(ToolCallScope.class);

        Map<String, Object> inputs = inputs();
        String program = wrap(language, code, json(inputs));
        logDebug("running %s code in the sandbox (%d chars)", language, code.length());
        String output = ToolInvokers.require().call(TOOL, Map.of("language", language, "code", program), scope);

        Map<String, Object> exported = parse(cfg.getName(), output);
        for (Map.Entry<String, Object> entry : exported.entrySet()) {
            // No resolver: what the program returns is data, never an expression to evaluate.
            getVariableScope().assignValueToParentScope(entry.getKey(), entry.getValue(), null);
        }
        setResult(exported.get("result"));
    }

    /** The variables the program sees: every one that is JSON, except the environment. */
    private Map<String, Object> inputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getVariableScope().getVariableValueMap().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank() || ENV_VARIABLE.equals(name) || !isIdentifier(name)) continue;
            try {
                MAPPER.writeValueAsString(entry.getValue());
                inputs.put(name, entry.getValue());
            } catch (Exception e) {
                logDebug("variable %s is not JSON — not passed to the sandbox", name);
            }
        }
        return inputs;
    }

    private static boolean isIdentifier(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * The step's variables out of the tool's output. A program that failed —
     * the tool reports an error, the exit code is not zero, or the line never
     * came — fails the step with what it printed.
     */
    static Map<String, Object> parse(String step, String output) {
        String text = output == null ? "" : output;
        if (text.stripLeading().startsWith("Error:")) {
            throw new IllegalStateException("code step '" + step + "': " + excerpt(text.strip()));
        }
        Matcher exit = EXIT_CODE.matcher(text);
        if (exit.find() && !"0".equals(exit.group(1))) {
            throw new IllegalStateException("code step '" + step + "' exited with " + exit.group(1) + ":\n"
                    + excerpt(text));
        }
        int at = text.lastIndexOf(MARKER);
        if (at < 0) {
            throw new IllegalStateException("code step '" + step + "' returned no variables — "
                    + "was the output cut off?\n" + excerpt(text));
        }
        int end = text.indexOf('\n', at);
        String line = text.substring(at + MARKER.length(), end < 0 ? text.length() : end).strip();
        try {
            return MAPPER.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("code step '" + step + "' returned variables that are not JSON: "
                    + e.getMessage(), e);
        }
    }

    private static String excerpt(String text) {
        return text.length() <= ERROR_EXCERPT ? text : "…" + text.substring(text.length() - ERROR_EXCERPT);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("variables could not be written as JSON: " + e.getMessage(), e);
        }
    }

    /**
     * The program as the sandbox runs it. Both the variables and the code go
     * in base64, so nothing in them can end a string literal early.
     */
    static String wrap(String language, String code, String inputsJson) {
        String in = base64(inputsJson);
        String src = base64(code);
        return switch (language) {
            case "python" -> PYTHON.replace("@IN@", in).replace("@SRC@", src).replace("@MARKER@", MARKER);
            case "node" -> NODE.replace("@IN@", in).replace("@SRC@", src).replace("@MARKER@", MARKER);
            default -> throw new IllegalArgumentException("code steps run python or node, not '" + language + "'");
        };
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The program runs in a namespace of its own, holding the variables; what
     * it leaves there under a name not starting with {@code _} and that is
     * JSON, new or changed, is returned.
     */
    private static final String PYTHON = """
            import base64 as _mc_b64, json as _mc_json
            _mc_in = _mc_json.loads(_mc_b64.b64decode("@IN@").decode("utf-8"))
            _mc_ns = dict(_mc_in)
            _mc_ns["__name__"] = "__main__"
            exec(compile(_mc_b64.b64decode("@SRC@").decode("utf-8"), "workflow-step", "exec"), _mc_ns)
            _mc_out = {}
            for _mc_k, _mc_v in _mc_ns.items():
                if _mc_k.startswith("_"):
                    continue
                try:
                    _mc_s = _mc_json.dumps(_mc_v, sort_keys=True, allow_nan=False)
                except (TypeError, ValueError):
                    continue
                if _mc_k not in _mc_in or _mc_json.dumps(_mc_in[_mc_k], sort_keys=True) != _mc_s:
                    _mc_out[_mc_k] = _mc_v
            print("\\n@MARKER@" + _mc_json.dumps(_mc_out, allow_nan=False), flush=True)
            """;

    /**
     * The program runs in a {@code vm} context holding the variables and the
     * usual globals. Top-level {@code var} and plain assignments land on the
     * context and come back; {@code let} and {@code const} stay local, as they
     * do at the top of any script. Synchronous code only: the variables are
     * read as soon as the program returns.
     */
    private static final String NODE = """
            const _mcVm = require('vm');
            const _mcDec = (s) => Buffer.from(s, 'base64').toString('utf8');
            const _mcIn = JSON.parse(_mcDec("@IN@"));
            const _mcGlobals = { console, require, Buffer, process, URL, TextEncoder, TextDecoder,
                setTimeout, clearTimeout, setInterval, clearInterval };
            const _mcCtx = Object.assign({}, _mcGlobals, _mcIn);
            _mcVm.createContext(_mcCtx);
            _mcVm.runInContext(_mcDec("@SRC@"), _mcCtx, { filename: 'workflow-step' });
            const _mcOut = {};
            for (const k of Object.keys(_mcCtx)) {
              if (k.startsWith('_')) continue;
              if (Object.prototype.hasOwnProperty.call(_mcGlobals, k) && _mcCtx[k] === _mcGlobals[k]) continue;
              let s;
              try { s = JSON.stringify(_mcCtx[k]); } catch (e) { continue; }
              if (s === undefined) continue;
              if (!(k in _mcIn) || JSON.stringify(_mcIn[k]) !== s) _mcOut[k] = JSON.parse(s);
            }
            process.stdout.write('\\n@MARKER@' + JSON.stringify(_mcOut) + '\\n');
            """;
}
