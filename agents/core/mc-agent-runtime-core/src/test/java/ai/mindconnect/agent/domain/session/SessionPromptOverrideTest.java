package ai.mindconnect.agent.domain.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chat may run on a prompt of its own while staying bound to its agent.
 *
 * <p>The binding is what matters here: detaching into an
 * {@link InlineSessionAgent} — the way this used to be done — drops the
 * agent's roster, and a chat that edited its prompt would quietly regain the
 * run of every agent in the namespace.
 */
class SessionPromptOverrideTest {

    private static SessionAgentRef ref(String prompt) {
        return new SessionAgentRef(UUID.randomUUID(), true, "default-chat", null, null, null, prompt);
    }

    @Test
    void noOverrideMeansTheAgentsOwnPrompt() {
        assertThat(ref(null).hasPromptOverride()).isFalse();
        assertThat(ref("").hasPromptOverride()).isFalse();
        assertThat(ref("   ").hasPromptOverride()).isFalse();
    }

    @Test
    void anOverrideIsRecognisedAsOne() {
        var r = ref("You only answer in haiku.");

        assertThat(r.hasPromptOverride()).isTrue();
        assertThat(r.systemPrompt()).isEqualTo("You only answer in haiku.");
    }

    /** The header names the agent, so the override has to be visible somewhere. */
    @Test
    void theBindingSurvivesTheOverride() {
        UUID agentId = UUID.randomUUID();
        var r = new SessionAgentRef(agentId, true, "default-chat", null, null, null, "mine");

        assertThat(r.agentId()).isEqualTo(agentId);
        assertThat(r.label()).isEqualTo("default-chat");
    }

    /** Sessions written before the field existed deserialise as "no override". */
    @Test
    void thePreOverrideConstructorStillWorks() {
        var r = new SessionAgentRef(UUID.randomUUID(), true, "Poet", "claude", null, null);

        assertThat(r.systemPrompt()).isNull();
        assertThat(r.hasPromptOverride()).isFalse();
    }
}
