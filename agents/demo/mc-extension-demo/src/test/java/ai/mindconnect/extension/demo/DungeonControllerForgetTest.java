package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.runtime.feature.Persistence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forgetting an adventure is for its player only: an id that belongs to
 * somebody else is no adventure of the caller's, and stays.
 */
class DungeonControllerForgetTest {

    @Test
    void only_the_player_can_forget_an_adventure() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.inMemory()).install(new DemoFeature()).build()) {
            DemoStore<Adventure> store = runtime.beans().get(AdventureStore.class).store();
            Adventure alices = Adventure.start(Scenario.SEED.get(0), "s-1", "alice");
            store.save(alices);

            new DungeonController(runtime, as("bob")).forget(alices.id());
            assertThat(store.find(alices.id())).as("somebody else's adventure stays").isPresent();

            new DungeonController(runtime, as("alice")).forget(alices.id());
            assertThat(store.find(alices.id())).as("the player's own is forgotten").isEmpty();
        }
    }

    private static ScopeSupplier as(String user) {
        return ScopeSupplier.fixed(Scope.of(Namespace.DEFAULT, UserId.of(user)));
    }
}
