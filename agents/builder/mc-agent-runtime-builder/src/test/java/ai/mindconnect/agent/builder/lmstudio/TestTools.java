package ai.mindconnect.agent.builder.lmstudio;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic tools for the LM Studio integration tests, discovered via the
 * regular {@code META-INF/services} SPI on the TEST classpath — the runtime
 * resolves them exactly like production tools. All three record their
 * invocations statically so tests can assert "ran / never ran"; call
 * {@link #reset()} between tests.
 */
public final class TestTools {

    /** Every executed (toolName, text) pair, in order. */
    public static final List<String> INVOCATIONS = new CopyOnWriteArrayList<>();

    /** Sleep of {@code it_slow} — long enough that a test can act mid-run. */
    public static volatile long slowMillis = 8_000;

    /** Size of {@code it_big}'s answer in characters. */
    public static volatile int bigChars = 8_000;

    public static void reset() {
        INVOCATIONS.clear();
        slowMillis = 8_000;
        bigChars = 8_000;
    }

    private static Map<String, Object> textSchema() {
        return Map.of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", List.of("text"));
    }

    private static String text(Map<String, Object> arguments) {
        Object value = arguments.get("text");
        return value == null ? "" : String.valueOf(value);
    }

    private abstract static class SimpleFactory implements ToolFactory {
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new Tool() {
                @Override public String name() { return SimpleFactory.this.name(); }
                @Override public String description() { return describe(); }
                @Override public Map<String, Object> parametersSchema() { return textSchema(); }
                @Override public String execute(Map<String, Object> arguments) {
                    return SimpleFactory.this.run(text(arguments));
                }
            };
        }
        abstract String describe();
        abstract String run(String text);
    }

    /** Echoes immediately — the approval-gated guinea pig. */
    public static final class EchoFactory extends SimpleFactory {
        @Override public String name() { return "it_echo"; }
        @Override String describe() { return "Echoes the given text back."; }
        @Override String run(String text) {
            INVOCATIONS.add("it_echo:" + text);
            return "ECHO:" + text;
        }
    }

    /** Echoes after {@link #slowMillis} — keeps the turn suspended while tests act. */
    public static final class SlowEchoFactory extends SimpleFactory {
        @Override public String name() { return "it_slow"; }
        @Override String describe() { return "Echoes the given text back, but takes a while."; }
        @Override String run(String text) {
            try {
                Thread.sleep(slowMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            INVOCATIONS.add("it_slow:" + text);
            return "SLOW:" + text;
        }
    }

    /** Returns {@link #bigChars} characters — compression fodder. */
    public static final class BigFactory extends SimpleFactory {
        @Override public String name() { return "it_big"; }
        @Override String describe() { return "Loads a large dataset. Returns a lot of text."; }
        @Override String run(String text) {
            INVOCATIONS.add("it_big:" + text);
            StringBuilder sb = new StringBuilder(bigChars + 64);
            sb.append("DATASET ").append(text).append('\n');
            while (sb.length() < bigChars) {
                sb.append("lorem ipsum data row ").append(sb.length()).append('\n');
            }
            return sb.toString();
        }
    }

    private TestTools() { }
}
