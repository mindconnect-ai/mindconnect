package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.sdk.AgentRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forgetting an adventure is for its player only: an id that belongs to
 * somebody else is no adventure of the caller's, and stays.
 */
class DungeonControllerForgetTest {

    @Test
    void only_the_player_can_forget_an_adventure() throws Exception {
        try (AgentRuntime runtime = AgentRuntime.builder().model(NOBODY).plugin(new DemoPlugin()).build()) {
            DemoStore<Adventure> store = runtime.service(AdventureStore.class).store();
            Adventure alices = Adventure.start(Scenario.SEED.get(0), "s-1", "alice");
            store.save(alices);

            new DungeonController(runtime, as("bob")).forget(alices.id());
            assertThat(store.find(alices.id())).as("somebody else's adventure stays").isPresent();

            new DungeonController(runtime, as("alice")).forget(alices.id());
            assertThat(store.find(alices.id())).as("the player's own is forgotten").isEmpty();
        }
    }

    /** A runtime needs a model to be built; nothing here asks it. */
    private static final ModelGateway NOBODY = new ModelGateway() {
        @Override
        public LlmAnswer ask(ModelRequest request, TurnEventListener events) {
            throw new UnsupportedOperationException("No model is asked in this test");
        }

        @Override
        public void cancel(TurnId turnId) {
        }
    };

    private static ScopeSupplier as(String user) {
        return ScopeSupplier.fixed(Scope.of(Namespace.DEFAULT, UserId.of(user)));
    }
}
