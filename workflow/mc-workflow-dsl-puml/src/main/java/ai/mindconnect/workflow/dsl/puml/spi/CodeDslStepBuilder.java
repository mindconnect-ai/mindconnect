package ai.mindconnect.workflow.dsl.puml.spi;

import ai.mindconnect.workflow.domain.CodeData;

import java.util.Map;

/**
 * Builds {@link CodeData} from DSL property lines.
 *
 * <p>Supported properties:
 * <ul>
 *   <li>{@code language} — JSR-223 engine name (default: {@code javascript})</li>
 *   <li>{@code code} — the script body; may also be given as a multi-line
 *       block between {@code ---code---} markers</li>
 *   <li>{@code wrapInFunction} — {@code true/false}</li>
 *   <li>{@code injectVariables} — {@code true/false}</li>
 *   <li>{@code exportVariables} — {@code true/false}</li>
 *   <li>{@code result} — sets {@code assignResultToVar}</li>
 * </ul>
 *
 * <p>Example (single-line code):
 * <pre>
 *   :code calcSquare
 *     language = javascript
 *     code = base * base
 *     result = squared
 *   ;
 * </pre>
 *
 * <p>Example (multi-line code block):
 * <pre>
 *   :code formatName
 *     language = javascript
 *     ---code---
 *     var full = firstName + ' ' + lastName;
 *     full.trim();
 *     ---code---
 *     result = fullName
 *   ;
 * </pre>
 */
public class CodeDslStepBuilder implements DslStepBuilder {

    @Override
    public String typeName() {
        return "code";
    }

    @Override
    public CodeData build(String name, Map<String, String> props) {
        CodeData data = new CodeData();
        data.setName(name);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            switch (entry.getKey().toLowerCase()) {
                case "language" -> data.setLanguage(entry.getValue());
                case "code" -> data.setCode(entry.getValue());
                case "wrapinfunction" -> data.setWrapInFunction(Boolean.parseBoolean(entry.getValue()));
                case "injectvariables" -> data.setInjectVariables(Boolean.parseBoolean(entry.getValue()));
                case "exportvariables" -> data.setExportVariables(Boolean.parseBoolean(entry.getValue()));
                case "assign-result", "result" -> data.setAssignResultToVar(entry.getValue());
                default -> { /* ignore unknown */ }
            }
        }
        return data;
    }
}
