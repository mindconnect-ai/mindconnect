package ai.mindconnect.namespace.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadBoundScopeSupplierTest {

    private static final Scope ACME = Scope.of(new Namespace("acme"));
    private static final Scope OTHER = Scope.of(new Namespace("other"));

    @Test
    void strictFailsWhenNothingIsBound() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();

        assertThatThrownBy(scope::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No scope is bound");
    }

    @Test
    void fallbackAnswersWhenNothingIsBound() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.withFallback(Scope.local());

        assertThat(scope.get()).isEqualTo(Scope.local());
        assertThat(scope.namespace()).isEqualTo(Namespace.DEFAULT);
        assertThat(scope.isBound()).isFalse();
    }

    @Test
    void runInBindsForTheBodyAndRestoresAfter() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();

        Scope seen = scope.runIn(ACME, () -> {
            assertThat(scope.isBound()).isTrue();
            return scope.get();
        });

        assertThat(seen).isEqualTo(ACME);
        assertThat(scope.isBound()).isFalse();
    }

    @Test
    void nestedRunInRestoresTheOuterBinding() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();

        scope.runIn(ACME, () -> {
            scope.runIn(OTHER, () -> assertThat(scope.get()).isEqualTo(OTHER));
            assertThat(scope.get()).isEqualTo(ACME);
        });
    }

    @Test
    void bindingIsRestoredEvenWhenTheBodyThrows() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.withFallback(Scope.local());

        assertThatThrownBy(() -> scope.runIn(ACME, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(scope.get()).isEqualTo(Scope.local());
    }

    @Test
    void aThreadStartedWhileBoundInheritsTheBinding() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        AtomicReference<Scope> seenOnChild = new AtomicReference<>();

        scope.runIn(ACME, () -> join(Thread.ofVirtual().start(() -> seenOnChild.set(scope.get()))));

        assertThat(seenOnChild.get()).isEqualTo(ACME);
    }

    @Test
    void aThreadStartedBeforeTheBindingStaysUnbound() throws Exception {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        pool.submit(() -> { }).get();                                    // the pool thread exists, unbound
        AtomicReference<Boolean> boundOnPool = new AtomicReference<>();

        scope.runIn(ACME, () -> {
            try {
                pool.submit(() -> boundOnPool.set(scope.isBound())).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        pool.shutdown();

        assertThat(boundOnPool.get()).isFalse();
    }

    @Test
    void aPerTaskExecutorInheritsFromTheSubmittingThread() throws Exception {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        java.util.concurrent.ExecutorService perTask = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<Scope> seen = new AtomicReference<>();

        scope.runIn(ACME, () -> {
            try {
                perTask.submit(() -> seen.set(scope.get())).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(seen.get()).isEqualTo(ACME);
    }

    @Test
    void wrapCarriesTheBindingAcrossAThreadHop() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        AtomicReference<Scope> seenOnOtherThread = new AtomicReference<>();

        scope.runIn(ACME, () -> join(Thread.ofVirtual().start(scope.wrap(() -> seenOnOtherThread.set(scope.get())))));

        assertThat(seenOnOtherThread.get()).isEqualTo(ACME);
    }

    @Test
    void callInLetsCheckedExceptionsThrough() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();

        assertThatThrownBy(() -> scope.callIn(ACME, () -> {
            throw new java.io.IOException("disk");
        })).isInstanceOf(java.io.IOException.class);
        assertThat(scope.isBound()).isFalse();
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
    @Test
    void aBindListenerSeesEachEntryIntoANamespaceWithTheScopeAlreadyBound() throws Exception {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        List<String> entries = new ArrayList<>();
        scope.onBind(entered -> entries.add(entered.namespace().value() + "=" + scope.namespace().value()));

        scope.runIn(ACME, () -> {
            scope.runIn(ACME, () -> { });              // stays in acme: no new entry
            scope.runIn(OTHER, () -> { });             // enters other
        });
        scope.callIn(ACME, () -> "done");

        assertThat(entries).containsExactly("acme=acme", "other=other", "acme=acme");
    }

    @Test
    void aFailingBindListenerFailsTheWorkAndLeavesNothingBound() {
        ThreadBoundScopeSupplier scope = ThreadBoundScopeSupplier.strict();
        scope.onBind(entered -> { throw new IllegalStateException("broken"); });

        assertThatThrownBy(() -> scope.runIn(ACME, () -> { })).hasMessage("broken");
        assertThat(scope.isBound()).isFalse();
    }
}
