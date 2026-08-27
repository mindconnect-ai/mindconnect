package ai.mindconnect.script.mini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the MiniScript for-each loop construct.
 */
class MiniScriptEngineForEachTest {

    private ScriptEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MiniScriptEngineFactory().getScriptEngine();
    }

    // ── basic iteration ───────────────────────────────────────────────────────

    @Test
    void forEach_overList_returnsListOfLastExpressionPerIteration() throws ScriptException {
        engine.put("items", List.of(1L, 2L, 3L));
        Object result = engine.eval("for (item in items) { item }");
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void forEach_doubleEachElement() throws ScriptException {
        engine.put("nums", List.of(1L, 2L, 3L));
        Object result = engine.eval("for (n in nums) { n * 2 }");
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(3);
        assertThat(((Number) list.get(0)).doubleValue()).isEqualTo(2.0);
        assertThat(((Number) list.get(1)).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) list.get(2)).doubleValue()).isEqualTo(6.0);
    }

    @Test
    void forEach_assignResultToVariable() throws ScriptException {
        engine.put("vals", List.of(10L, 20L, 30L));
        engine.eval("doubled = for (v in vals) { v * 2 }");
        Object doubled = engine.get("doubled");
        assertThat(doubled).isInstanceOf(List.class);
        List<?> list = (List<?>) doubled;
        assertThat(((Number) list.get(0)).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) list.get(1)).doubleValue()).isEqualTo(40.0);
        assertThat(((Number) list.get(2)).doubleValue()).isEqualTo(60.0);
    }

    @Test
    void forEach_emptyList_returnsEmptyList() throws ScriptException {
        engine.put("empty", List.of());
        Object result = engine.eval("for (x in empty) { x }");
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).isEmpty();
    }

    // ── index variable ────────────────────────────────────────────────────────

    @Test
    void forEach_withIndex_indexStartsAtZero() throws ScriptException {
        engine.put("items", List.of("a", "b", "c"));
        engine.eval("indices = for (item, i in items) { i }");
        List<?> indices = (List<?>) engine.get("indices");
        assertThat(indices).hasSize(3);
        assertThat(((Number) indices.get(0)).longValue()).isEqualTo(0L);
        assertThat(((Number) indices.get(1)).longValue()).isEqualTo(1L);
        assertThat(((Number) indices.get(2)).longValue()).isEqualTo(2L);
    }

    @Test
    void forEach_withIndex_itemAndIndexBothAvailable() throws ScriptException {
        engine.put("names", List.of("Alice", "Bob"));
        engine.eval("labels = for (name, idx in names) { str(idx) + \": \" + name }");
        List<?> labels = (List<?>) engine.get("labels");
        assertThat(labels).isEqualTo(List.of("0: Alice", "1: Bob"));
    }

    @Test
    void forEach_withIndex_indexVarWrittenBackToScope() throws ScriptException {
        engine.put("items", List.of("x", "y", "z"));
        engine.eval("for (item, i in items) { i }");
        // After the loop, i should hold the last index value (2)
        Object i = engine.get("i");
        assertThat(((Number) i).longValue()).isEqualTo(2L);
    }

    // ── string iteration ──────────────────────────────────────────────────────

    @Test
    void forEach_overString_iteratesCharacters() throws ScriptException {
        engine.put("word", "abc");
        Object result = engine.eval("for (ch in word) { ch }");
        List<?> chars = (List<?>) result;
        assertThat(chars).isEqualTo(List.of("a", "b", "c"));
    }

    // ── map iteration ─────────────────────────────────────────────────────────

    @Test
    void forEach_overMap_producesOneEntryPerKey() throws ScriptException {
        // Use a LinkedHashMap for predictable order
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", 1L);
        m.put("b", 2L);
        engine.put("m", m);
        Object result = engine.eval("for (entry in m) { entry }");
        List<?> entries = (List<?>) result;
        assertThat(entries).hasSize(2);
    }

    // ── array iteration ───────────────────────────────────────────────────────

    @Test
    void forEach_overArray_iteratesElements() throws ScriptException {
        engine.put("arr", new String[]{"x", "y", "z"});
        Object result = engine.eval("for (el in arr) { el }");
        List<?> list = (List<?>) result;
        assertThat(list).isEqualTo(List.of("x", "y", "z"));
    }

    @Test
    void forEach_overIntArray_iteratesElements() throws ScriptException {
        engine.put("arr", new int[]{1, 2, 3});
        Object result = engine.eval("for (n in arr) { n }");
        List<?> list = (List<?>) result;
        assertThat(list).hasSize(3);
        assertThat(((Number) list.get(0)).intValue()).isEqualTo(1);
        assertThat(((Number) list.get(1)).intValue()).isEqualTo(2);
        assertThat(((Number) list.get(2)).intValue()).isEqualTo(3);
    }

    // ── loop body side-effects ────────────────────────────────────────────────

    @Test
    void forEach_bodyCanCallMethodsOnEachItem() throws ScriptException {
        engine.put("strings", List.of("hello", "world"));
        Object result = engine.eval("for (s in strings) { s.toUpperCase() }");
        List<?> list = (List<?>) result;
        assertThat(list).isEqualTo(List.of("HELLO", "WORLD"));
    }

    @Test
    void forEach_nestedLoopCollectsResultsFromInnerLoop() throws ScriptException {
        engine.put("matrix", List.of(List.of(1L, 2L), List.of(3L, 4L)));
        Object result = engine.eval("for (row in matrix) { for (cell in row) { cell } }");
        List<?> outer = (List<?>) result;
        assertThat(outer).hasSize(2);
        assertThat((List<?>) outer.get(0)).isEqualTo(List.of(1L, 2L));
        assertThat((List<?>) outer.get(1)).isEqualTo(List.of(3L, 4L));
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Test
    void forEach_overNull_throwsScriptException() {
        engine.put("nothing", null);
        assertThatThrownBy(() -> engine.eval("for (x in nothing) { x }"))
                .isInstanceOf(ScriptException.class)
                .hasMessageContaining("null");
    }

    @Test
    void forEach_overNonIterable_throwsScriptException() {
        engine.put("num", 42L);
        assertThatThrownBy(() -> engine.eval("for (x in num) { x }"))
                .isInstanceOf(ScriptException.class);
    }
}
