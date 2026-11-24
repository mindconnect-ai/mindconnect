package ai.mindconnect.agent.starter.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.management.ManagementPlugin;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.infrastructure.InMemoryStorePlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tools.workflow.WorkflowAgentCalls;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** An agent-call step against the runtime: a turn of a stored agent, in a session of the run's own. */
class RuntimeAgentInvokerTest {

    @TempDir
    Path side;

    private final Deque<TurnMessage> script = new ArrayDeque<>();
    private final ModelGateway model = new ModelGateway() {
        @Override
        public synchronized LlmAnswer ask(ModelRequest request, TurnEventListener events) {
            return LlmAnswer.of(List.of(script.pop()), new Usage(10, 1));
        }

        @Override
        public void cancel(TurnId turnId) {
        }
    };

    private AgentRuntime runtime;
    private RuntimeAgentInvoker invoker;

    @BeforeEach
    void build() {
        ScopeSupplier scope = ScopeSupplier.fixed(Scope.of(new Namespace("test")));
        runtime = AgentRuntime.builder()
                .model(model)
                .tool(sendMail())
                // A tool nobody ruled on asks first: what the server does for a tool that needs approval.
                .unruledTools(PermissionEffect.ASK)
                .plugin(new Plugin() {
                    @Override
                    public String name() {
                        return "scope";
                    }

                    @Override
                    public void install(PluginRegistry registry) {
                        registry.provide(ScopeSupplier.class, () -> scope);
                    }
                })
                .plugin(new InMemoryStorePlugin(side, 10))
                .plugin(new ManagementPlugin())
                .build();
        invoker = new RuntimeAgentInvoker(runtime, Duration.ofSeconds(10));
    }

    @AfterEach
    void close() {
        runtime.close();
    }

    @Test
    void the_named_agent_answers_in_a_session_of_the_runs_own() {
        runtime.service(AgentManagement.class).create(ManagedAgent.of(agent("writer", List.of())));
        script.add(TurnMessage.assistant("Done."));

        assertThat(invoker.call("writer", "Write it.")).isEqualTo("Done.");

        List<ManagedSession> opened = runtime.service(SessionManagement.class)
                .list(UserId.of(WorkflowAgentCalls.WORKFLOW_USER));
        assertThat(opened).as("kept out of a user's chats by its type").extracting(ManagedSession::type)
                .containsExactly(WorkflowAgentCalls.SESSION_TYPE);
    }

    @Test
    void a_turn_that_asks_for_an_approval_fails_the_step_and_is_given_up() {
        runtime.service(AgentManagement.class).create(
                ManagedAgent.of(agent("mailer", ToolBinding.allOf("mail_send"))));
        script.add(TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "mail_send", Map.of()))));

        assertThatThrownBy(() -> invoker.call("mailer", "Send it."))
                .hasMessageContaining("approval of mail_send");
    }

    @Test
    void an_unknown_agent_is_named() {
        assertThatThrownBy(() -> invoker.call("nobody", "Hi")).hasMessageContaining("No agent named 'nobody'");
    }

    @Test
    void an_inline_agent_is_created_and_then_brought_up_to_its_description() {
        invoker.upsertAgent(Map.of("name", "inline", "systemPrompt", "First.", "llmConfigName", "small",
                "tools", List.of(Map.of("name", "mail_send", "tool", "smtp"))));
        invoker.upsertAgent(Map.of("name", "inline", "systemPrompt", "Second."));

        AgentDefinition stored = runtime.service(AgentManagement.class).findByName("inline").orElseThrow()
                .definition();
        assertThat(stored.instructions()).isEqualTo("Second.");
        assertThat(stored.modelSettings().model()).as("what the spec leaves out stays").isEqualTo("small");
        assertThat(stored.tools()).as("the tools are the ones the spec names").isEmpty();
    }

    private static AgentDefinition agent(String name, List<ToolBinding> tools) {
        return new AgentDefinition(name, "", "You work.", ModelSettings.ofModel("any"), tools, 5, Map.of());
    }

    private static Tool sendMail() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("mail_send", "Sends a mail.", Map.of("type", "object"));
            }

            @Override
            public ToolOutput execute(ToolCall call, Session session) {
                return ToolOutput.ok("sent");
            }
        };
    }
}
