package ai.mindconnect.script.mini;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.List;

/**
 * JSR-223 {@link ScriptEngineFactory} for {@link MiniScriptEngine}.
 *
 * <p>Can be used directly or discovered automatically via
 * {@code META-INF/services/javax.script.ScriptEngineFactory} through the
 * standard {@link javax.script.ScriptEngineManager}.
 *
 * <p>Usage:
 * <pre>{@code
 * ScriptEngine engine = new MiniScriptEngineFactory().getScriptEngine();
 * engine.put("x", 21);
 * Object result = engine.eval("x * 2");   // → 42.0
 * }</pre>
 */
public class MiniScriptEngineFactory implements ScriptEngineFactory {

    @Override public String  getEngineName()      { return "MiniScript"; }
    @Override public String  getEngineVersion()   { return "1.0"; }
    @Override public String  getLanguageName()    { return "miniscript"; }
    @Override public String  getLanguageVersion() { return "1.0"; }
    @Override public List<String> getNames()      { return List.of("miniscript", "mini"); }
    @Override public List<String> getExtensions() { return List.of("mini"); }
    @Override public List<String> getMimeTypes()  { return List.of("text/x-miniscript"); }

    @Override
    public Object getParameter(String key) {
        return switch (key) {
            case ScriptEngine.ENGINE           -> getEngineName();
            case ScriptEngine.ENGINE_VERSION   -> getEngineVersion();
            case ScriptEngine.LANGUAGE         -> getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION -> getLanguageVersion();
            case ScriptEngine.NAME             -> getNames().get(0);
            default                            -> null;
        };
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return obj + "." + m + "(" + String.join(", ", args) + ")";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "println(" + toDisplay + ")";
    }

    @Override
    public String getProgram(String... statements) {
        return String.join("\n", statements);
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new MiniScriptEngine(this);
    }
}
