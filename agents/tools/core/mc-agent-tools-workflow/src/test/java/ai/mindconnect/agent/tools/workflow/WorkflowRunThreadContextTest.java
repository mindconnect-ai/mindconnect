package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.workflow.admin.run.RunThreadContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workflow run started from the admin screens acts for the signed-in user:
 * its tool steps resolve their tools for them, so a {@code mail_list} step
 * finds their mailboxes. It used to run as the workflow user — "No account of
 * this kind is connected" — because only the namespace was carried along.
 */
class WorkflowRunThreadContextTest {

    private static final Namespace NS = new Namespace("team");
    private static final UserId DAVID = UserId.of("david");

    private final ThreadBoundScope scope = ThreadBoundScope.strict();
    private final RunThreadContext context =
            new NamespacedWorkflowStoresAutoConfiguration.AdminRuns().workflowRunThreadContext(scope);

    @Test
    void aStreamedRunWorksInTheRequestsNamespaceAndActsForItsUser() {
        AtomicReference<Scope> seenScope = new AtomicReference<>();
        AtomicReference<Optional<ToolCallScope>> seenCaller = new AtomicReference<>();
        // Wrapped while the request is bound, run after it is gone — as the stream's executor does.
        Runnable carried = scope.runIn(Scope.of(NS, DAVID), () -> context.carry(() -> {
            seenScope.set(scope.get());
            seenCaller.set(ToolCallScope.current());
        }));

        carried.run();

        assertThat(seenScope.get()).isEqualTo(Scope.of(NS, DAVID));
        assertThat(seenCaller.get()).map(ToolCallScope::userId).contains(DAVID);
        assertThat(ToolCallScope.current()).isEmpty();
    }

    @Test
    void aRunOnTheRequestThreadActsForItsUserToo() {
        Optional<UserId> caller = scope.runIn(Scope.of(NS, DAVID),
                () -> context.call(() -> ToolCallScope.current().map(ToolCallScope::userId)));

        assertThat(caller).contains(DAVID);
        assertThat(ToolCallScope.current()).isEmpty();
    }

    @Test
    void aRequestWithoutAUserStartsARunOnNobodysBehalf() {
        Optional<ToolCallScope> caller = scope.runIn(Scope.of(NS), () -> context.call(ToolCallScope::current));

        assertThat(caller).isEmpty();
    }

    @Test
    void aToolCallScopeAlreadyOnTheThreadWins() {
        ToolCallScope session = ToolCallScope.detached(UserId.of("uploader"));

        Optional<UserId> caller = session.runWith(() -> scope.runIn(Scope.of(NS, DAVID),
                () -> context.call(() -> ToolCallScope.current().map(ToolCallScope::userId))));

        assertThat(caller).contains(UserId.of("uploader"));
    }

    @Test
    void anUnboundThreadIsNobodyEvenWithAFallbackScope() {
        ThreadBoundScope withFallback = ThreadBoundScope.withFallback(Scope.of(NS, DAVID));
        RunThreadContext unbound =
                new NamespacedWorkflowStoresAutoConfiguration.AdminRuns().workflowRunThreadContext(withFallback);

        assertThat(unbound.call(ToolCallScope::current)).isEmpty();
    }

    @Test
    void aFixedScopeWithAUserActsForThem() {
        RunThreadContext fixed = new NamespacedWorkflowStoresAutoConfiguration.AdminRuns()
                .workflowRunThreadContext(ScopeSupplier.fixed(Scope.of(NS, DAVID)));

        assertThat(fixed.call(() -> ToolCallScope.current().map(ToolCallScope::userId))).contains(DAVID);
    }
}
