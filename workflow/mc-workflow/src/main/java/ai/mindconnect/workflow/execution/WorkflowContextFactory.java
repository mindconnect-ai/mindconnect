package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.scripting.ScriptExecutor;
import ai.mindconnect.workflow.util.StringVariableReplacer;
import lombok.Data;

import java.io.Writer;
import java.util.ArrayList;

/** Produces {@link WorkflowContext} instances with a shared configuration. */
@Data
public class WorkflowContextFactory {

    private String baseDir;
    private CompositeExpressionResolver expressionResolver;
    private StringVariableReplacer stringVariableReplacer;
    private StepInstanceFactory stepInstanceFactory;
    private JsonMapper jsonMapper;
    private WorkflowDefinitionRegistry workflowDefinitionRegistry;

    /**
     * Shared script engine registry used by code steps and inline script expressions.
     * Language modules register their {@link javax.script.ScriptEngineFactory} here.
     * Stored as {@link Object} to avoid a compile-time dependency on
     * {@code mc-workflow-code} from {@code mc-workflow-api}.
     * Cast to {@code ScriptExecutor} in modules that depend on {@code mc-workflow-code}.
     */
    private ScriptExecutor scriptExecutor;

    /**
     * Optional writer that receives output from {@code print()} / {@code println()}
     * calls inside code steps.  When {@code null} (the default), output goes to
     * {@code System.out}.  Set this in the UI layer to capture script output in
     * the execution panel.
     */
    private Writer scriptOutputWriter;

    public WorkflowContext instantiate(String id) {
        return new WorkflowContext(
                id,
                baseDir,
                expressionResolver,
                stringVariableReplacer,
                scriptExecutor,
                stepInstanceFactory,
                scriptOutputWriter,
                jsonMapper,
                workflowDefinitionRegistry,
                new ArrayList<>()
        );
    }
}
