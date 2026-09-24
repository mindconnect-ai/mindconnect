package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceFirstUseTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final Namespace OTHER = new Namespace("other");

    @Test
    void the_work_runs_once_per_namespace_on_its_first_binding() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        List<String> ran = new CopyOnWriteArrayList<>();
        scope.onBind(new NamespaceFirstUse("test", ns -> ran.add(ns.value() + " bound=" + scope.namespace().value())));

        for (int i = 0; i < 3; i++) {
            scope.runIn(Scope.of(ACME), () -> { });
            scope.runIn(Scope.of(OTHER), () -> { });
        }

        assertThat(ran).containsExactly("acme bound=acme", "other bound=other");
    }

    @Test
    void work_that_binds_its_namespace_again_does_not_start_over() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        AtomicInteger runs = new AtomicInteger();
        scope.onBind(new NamespaceFirstUse("test", ns -> {
            runs.incrementAndGet();
            scope.runIn(Scope.of(OTHER), () -> scope.runIn(Scope.of(ns), () -> { }));
        }));

        scope.runIn(Scope.of(ACME), () -> { });

        assertThat(runs).as("acme once, other once from inside acme's work").hasValue(2);
    }

    @Test
    void a_second_thread_waits_until_the_first_has_prepared_the_namespace() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        NamespaceFirstUse firstUse = new NamespaceFirstUse("test", ns -> {
            runs.incrementAndGet();
            started.countDown();
            await(release);
        });

        Thread first = Thread.ofVirtual().start(() -> firstUse.ensure(ACME));
        await(started);
        CountDownLatch secondDone = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            firstUse.ensure(ACME);
            secondDone.countDown();
        });

        assertThat(secondDone.await(200, TimeUnit.MILLISECONDS)).as("waits while the work runs").isFalse();
        release.countDown();
        assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
        first.join();
        assertThat(runs).hasValue(1);
    }

    @Test
    void a_failure_is_logged_and_counts_as_done_until_forgotten() {
        AtomicInteger runs = new AtomicInteger();
        NamespaceFirstUse firstUse = new NamespaceFirstUse("test", ns -> {
            runs.incrementAndGet();
            throw new IllegalStateException("broken seed");
        });

        assertThat(firstUse.ensure(ACME)).isTrue();
        assertThat(firstUse.ensure(ACME)).isFalse();
        assertThat(firstUse.isDone(ACME)).isTrue();

        firstUse.forget(ACME);
        assertThat(firstUse.ensure(ACME)).isTrue();
        firstUse.forgetAll();
        assertThat(firstUse.isDone(ACME)).isFalse();
        assertThat(runs).hasValue(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
