package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tools.code.CodeLanguages.CodeLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Wires {@code code_execute} into the runtime. The tool is only offered when a
 * container binary (podman or docker, see {@link ContainerCli#detect}) answers
 * at bind time — no runtime installed simply means no tool, not a broken one.
 *
 * <p>Configuration (all optional, via ToolEnvironment strings):
 * <ul>
 *   <li>{@code codeExecRuntime} — {@code auto} (default) | {@code docker} |
 *       {@code podman} | path to a docker-CLI-compatible binary</li>
 *   <li>{@code codeExecLanguages} — image overrides / extra languages, see
 *       {@link CodeLanguages#parse}</li>
 *   <li>{@code codeExecNetwork} — default container network mode, {@code none}
 *       (default) or {@code bridge}; agents may override per tool via the
 *       {@code network} entry in {@link AgentTool#overrides()}</li>
 *   <li>{@code codeExecMemory} / {@code codeExecCpus} — per-container limits
 *       (default 512m / 1)</li>
 *   <li>{@code codeExecTimeoutSeconds} — per-execution wall clock (default 60)</li>
 *   <li>{@code codeExecIdleSeconds} — session container idle reaping (default 600)</li>
 *   <li>{@code dataBaseDir} — scratch dirs live under {@code <dataBaseDir>/code-exec}</li>
 * </ul>
 */
public final class CodeExecuteToolFactory implements ToolFactory {

    private static final Logger log = LoggerFactory.getLogger(CodeExecuteToolFactory.class);

    private Map<String, CodeLanguage> languages = CodeLanguages.defaults();
    private CodeExecutionService service;
    private String defaultNetwork = "none";

    @Override
    public String name() {
        return "code_execute";
    }

    @Override
    public String group() {
        return "code";
    }

    @Override
    public void bind(ToolEnvironment env) {
        String runtime = env.getString("codeExecRuntime").orElse("auto");
        ContainerCli cli = ContainerCli.detect(runtime).orElse(null);
        if (cli == null) {
            log.info("code_execute disabled: no container runtime found (codeExecRuntime={})", runtime);
            return;
        }
        this.languages = CodeLanguages.parse(env.getString("codeExecLanguages").orElse(null));
        this.defaultNetwork = networkOrDefault(env.getString("codeExecNetwork").orElse(null), "none");
        Path scratchRoot = Path.of(env.getString("dataBaseDir").orElse("data")).resolve("code-exec");
        CodeExecutionService.Settings settings = new CodeExecutionService.Settings(
                env.getString("codeExecMemory").orElse("512m"),
                env.getString("codeExecCpus").orElse("1"),
                Duration.ofSeconds(longValue(env, "codeExecTimeoutSeconds", 60)),
                Duration.ofSeconds(longValue(env, "codeExecIdleSeconds", 600)),
                scratchRoot);
        this.service = new CodeExecutionService(cli, settings);
        // The registry has no shutdown callback for factories; the containers
        // must not outlive the JVM regardless of how it exits.
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "code-exec-shutdown"));
        log.info("code_execute ready: backend={}, languages={}", cli.binary(), languages.keySet());
    }

    private static long longValue(ToolEnvironment env, String key, long fallback) {
        return env.getString(key).map(Long::parseLong).orElse(fallback);
    }

    @Override
    public boolean isAvailable() {
        return service != null;
    }

    @Override
    public Map<String, Object> overridesSchema() {
        Map<String, Object> network = new java.util.LinkedHashMap<>();
        network.put("type", "string");
        network.put("enum", java.util.List.of("none", "bridge"));
        network.put("default", defaultNetwork);
        network.put("description", "Container network mode. 'bridge' enables internet access "
                + "(HTTP requests, pip/npm installs); 'none' fully isolates the container.");
        return Map.of("type", "object", "properties", Map.of("network", network));
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        // Per-agent opt-in to network access via the agent-tool override
        // {"network": "bridge"} — the runtime default stays authoritative
        // for everything unlisted or invalid.
        String network = agentTool == null ? defaultNetwork
                : networkOrDefault(String.valueOf(agentTool.overrides().getOrDefault("network", "")), defaultNetwork);
        return new CodeExecuteTool(service, languages, sessionKey(scope), network);
    }

    /** Allowlist: only {@code none} and {@code bridge} are accepted network modes. */
    private static String networkOrDefault(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        if ("none".equals(candidate) || "bridge".equals(candidate)) {
            return candidate;
        }
        log.warn("Ignoring invalid code-exec network mode '{}' (allowed: none, bridge)", candidate);
        return fallback;
    }

    /**
     * Container/scratch-dir key: the chat session when there is one, so agent
     * calls within one conversation share warm state; a shared fallback
     * otherwise. Sanitised because it becomes a directory name.
     */
    private static String sessionKey(ToolCallScope scope) {
        String raw = scope != null && scope.sessionId() != null ? scope.sessionId().toString() : "shared";
        return raw.replaceAll("[^A-Za-z0-9_-]", "-");
    }
}
