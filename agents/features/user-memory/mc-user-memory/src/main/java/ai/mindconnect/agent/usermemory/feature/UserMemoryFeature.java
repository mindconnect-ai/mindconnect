package ai.mindconnect.agent.usermemory.feature;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.service.prompt.PromptSection;
import ai.mindconnect.agent.usermemory.MemoryIndex;
import ai.mindconnect.agent.usermemory.UserMemoryRepository;
import ai.mindconnect.agent.usermemory.UserMemoryService;
import ai.mindconnect.agent.usermemory.adapter.file.FileUserMemoryRepository;
import ai.mindconnect.agent.usermemory.adapter.memory.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.usermemory.adapter.pg.PgUserMemoryRepository;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.kernel.Feature;
import ai.mindconnect.kernel.FeatureContext;

/**
 * What agents remember about each user across chats, as a feature of its own:
 * the store on the runtime's persistence, the {@link UserMemoryService} the
 * memory tools and the admin screens use, and the memory index an agent sees
 * in its system prompt. The tools themselves are found on the classpath like
 * every other tool.
 */
public class UserMemoryFeature implements Feature {

    @Override
    public String name() {
        return "user-memory";
    }

    @Override
    public void configure(FeatureContext ctx) {
        ctx.bean(UserMemoryRepository.class, () -> {
            ScopeSupplier scope = ctx.require(ScopeSupplier.class);
            return switch (ctx.require(Persistence.class)) {
                case Persistence.File f -> new FileUserMemoryRepository(f.dataDir(), scope);
                case Persistence.Postgres p -> new PgUserMemoryRepository(ctx.require(Sql.class), scope).initSchema();
                case Persistence.InMemory m -> new InMemoryUserMemoryRepository(scope);
            };
        });
        ctx.bean(UserMemoryService.class, () -> new UserMemoryService(ctx.require(UserMemoryRepository.class)));
        ctx.contribute(PromptSection.class, new LazyMemoryIndex(ctx));
    }

    /** The memory index, its service resolved on first use: contributions are instances, beans are lazy. */
    private static final class LazyMemoryIndex implements PromptSection {

        private final FeatureContext ctx;
        private volatile PromptSection delegate;

        LazyMemoryIndex(FeatureContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String render(AgentDefinition def, AgentSession session) {
            if (delegate == null) delegate = new MemoryIndex(ctx.require(UserMemoryService.class));
            return delegate.render(def, session);
        }
    }
}
