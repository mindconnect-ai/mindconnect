package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.tools.workflow.step.SandboxCodeData;
import ai.mindconnect.agent.tools.workflow.step.SandboxCodeStep;
import ai.mindconnect.script.mini.MiniScriptEngineFactory;
import ai.mindconnect.workflow.execution.CompositeExpressionResolver;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.jackson.JsonExpressionResolver;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import ai.mindconnect.workflow.scripting.ScriptExpressionResolver;
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;

import javax.script.ScriptEngine;

/**
 * The engine a project's workflow runs on. Everything the SPI installs —
 * step types, the JSON mapper — except the script engines: an expression
 * anywhere in the workflow ({@code if}, {@code set}, a tool's arguments, an
 * input a model supplied) is evaluated by the {@linkplain
 * MiniScriptEngineFactory#restricted() restricted MiniScript} or parsed as
 * {@code json:}, and nothing else. A {@code javascript: …} string is then
 * just a string. Code runs in the sandbox, through {@link SandboxCodeStep}.
 */
final class RestrictedWorkflowContext {

    /** The only expression language a project workflow gets. */
    static final String MINI = "mini";

    private RestrictedWorkflowContext() {}

    static WorkflowContextFactory create() {
        WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
        ScriptExecutor scripts = new MiniOnlyScriptExecutor();
        scripts.register(MINI, MiniScriptEngineFactory.restricted());
        factory.setScriptExecutor(scripts);
        factory.setExpressionResolver(new CompositeExpressionResolver(factory.getStringVariableReplacer(),
                new ScriptExpressionResolver(scripts),
                new JsonExpressionResolver(WorkflowObjectMapperFactory.create())));
        factory.getStepInstanceFactory().register(SandboxCodeData.class, SandboxCodeStep::new);
        return factory;
    }

    /**
     * A {@link ScriptExecutor} that does not fall back to the engines on the
     * classpath: the plain one hands a code step asking for {@code groovy}
     * whatever Groovy it finds.
     */
    private static final class MiniOnlyScriptExecutor extends ScriptExecutor {
        @Override
        public ScriptEngine getEngine(String language) {
            if (!MINI.equals(language)) {
                throw new IllegalStateException("A project workflow evaluates mini expressions only, not '"
                        + language + "'; code runs in the sandbox");
            }
            return super.getEngine(language);
        }
    }
}
