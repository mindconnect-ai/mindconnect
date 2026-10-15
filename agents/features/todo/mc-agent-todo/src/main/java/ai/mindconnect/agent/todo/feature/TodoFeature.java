package ai.mindconnect.agent.todo.feature;

import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.runtime.port.out.SessionCleanup;
import ai.mindconnect.agent.todo.TodoContinuationAdvisor;
import ai.mindconnect.agent.todo.TodoListPromptContextProvider;
import ai.mindconnect.agent.todo.TodoListRepository;
import ai.mindconnect.agent.todo.TodoListService;
import ai.mindconnect.agent.todo.adapter.file.FileTodoListRepository;
import ai.mindconnect.agent.todo.adapter.memory.InMemoryTodoListRepository;
import ai.mindconnect.agent.todo.adapter.pg.PgTodoListRepository;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.kernel.Feature;
import ai.mindconnect.kernel.FeatureContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The working todo list of a session, as a feature of its own: the store on
 * the runtime's persistence, the {@link TodoListService} the todo tools and the
 * admin screens use, the list in the prompt context, the hint after a tool call
 * while items are open, and the list going with its session. The tools
 * themselves are found on the classpath like every other tool.
 */
public class TodoFeature implements Feature {

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public void configure(FeatureContext ctx) {
        ctx.bean(TodoListRepository.class, () -> {
            ScopeSupplier scope = ctx.require(ScopeSupplier.class);
            return switch (ctx.require(Persistence.class)) {
                case Persistence.File f -> new FileTodoListRepository(f.dataDir(), ctx.require(ObjectMapper.class), scope);
                case Persistence.Postgres p -> new PgTodoListRepository(ctx.require(Sql.class), scope).initSchema();
                case Persistence.InMemory m -> new InMemoryTodoListRepository(scope);
            };
        });
        ctx.bean(TodoListService.class, () -> new TodoListService(ctx.require(TodoListRepository.class)));
        // Contributions are instances, so these resolve the service on first use rather than now.
        ctx.contribute(PromptContextProvider.class, new LazyTodoPromptContext(ctx));
        ctx.contribute(ToolAdvisor.class, new LazyTodoContinuation(ctx));
        ctx.contribute(SessionCleanup.class, sessionId -> ctx.require(TodoListService.class).clear(sessionId));
    }

    private static final class LazyTodoPromptContext implements PromptContextProvider {

        private final FeatureContext ctx;
        private volatile PromptContextProvider delegate;

        LazyTodoPromptContext(FeatureContext ctx) {
            this.ctx = ctx;
        }

        private PromptContextProvider delegate() {
            if (delegate == null) delegate = new TodoListPromptContextProvider(ctx.require(TodoListService.class));
            return delegate;
        }

        @Override
        public int priority() {
            return delegate().priority();
        }

        @Override
        public void contribute(Map<String, Object> promptContext, AgentDefinition def,
                               AgentSession session, AuthenticationInfo auth) {
            delegate().contribute(promptContext, def, session, auth);
        }
    }

    private static final class LazyTodoContinuation implements ToolAdvisor {

        private final FeatureContext ctx;
        private volatile ToolAdvisor delegate;

        LazyTodoContinuation(FeatureContext ctx) {
            this.ctx = ctx;
        }

        private ToolAdvisor delegate() {
            if (delegate == null) delegate = new TodoContinuationAdvisor(ctx.require(TodoListService.class));
            return delegate;
        }

        @Override
        public Result around(Invocation inv, Chain chain) throws Exception {
            return delegate().around(inv, chain);
        }

        @Override
        public int order() {
            return delegate().order();
        }

        @Override
        public boolean applies(Invocation inv) {
            return delegate().applies(inv);
        }
    }
}
