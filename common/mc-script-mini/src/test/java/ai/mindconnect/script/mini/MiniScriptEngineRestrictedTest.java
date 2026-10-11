package ai.mindconnect.script.mini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * The restricted engine: expressions still work on plain values, but nothing
 * reaches a class, a file or the process.
 */
class MiniScriptEngineRestrictedTest {

    private ScriptEngine engine;

    @BeforeEach
    void setUp() {
        engine = MiniScriptEngineFactory.restricted().getScriptEngine();
    }

    @Test
    void conditionsAndArithmetic_work() throws ScriptException {
        engine.put("score", 93L);
        assertThat(engine.eval("score >= 90 && score < 100")).isEqualTo(true);
    }

    @Test
    void publicMethodsOfValueTypes_work() throws ScriptException {
        engine.put("name", "  Report ");
        engine.put("items", new ArrayList<>(List.of("a", "b")));
        engine.put("person", Map.of("city", "Bern"));
        assertThat(engine.eval("name.trim().toLowerCase()")).isEqualTo("report");
        assertThat(engine.eval("items.size()")).isEqualTo(2);
        assertThat(engine.eval("items.contains(\"b\")")).isEqualTo(true);
        assertThat(engine.eval("person.city")).isEqualTo("Bern");
        assertThat(engine.eval("person.containsKey(\"city\")")).isEqualTo(true);
    }

    @Test
    void getClass_isNotReachable() {
        assertThatThrownBy(() -> engine.eval("\"x\".getClass()"))
                .isInstanceOf(ScriptException.class)
                .hasMessageContaining("Method not found");
        assertThatThrownBy(() -> engine.eval("\"x\".class"))
                .isInstanceOf(ScriptException.class);
    }

    @Test
    void otherObjects_cannotBeCalled() {
        engine.put("file", new File("/"));
        assertThatThrownBy(() -> engine.eval("file.delete()"))
                .isInstanceOf(ScriptException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> engine.eval("file.path"))
                .isInstanceOf(ScriptException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void implementationMethods_areNotReachable() {
        // ArrayList has trimToSize, List does not: only the interface counts.
        engine.put("items", new ArrayList<>(List.of("a")));
        assertThatThrownBy(() -> engine.eval("items.trimToSize()"))
                .isInstanceOf(ScriptException.class)
                .hasMessageContaining("Method not found");
    }

    @Test
    void unrestrictedEngine_isUnchanged() throws ScriptException {
        ScriptEngine open = new MiniScriptEngineFactory().getScriptEngine();
        assertThat(open.eval("\"x\".getClass().getSimpleName()")).isEqualTo("String");
    }
}
