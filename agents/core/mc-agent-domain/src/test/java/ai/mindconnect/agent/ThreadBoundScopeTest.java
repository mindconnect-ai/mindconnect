package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadBoundScopeTest {

    private static final Scope ACME = Scope.of(new Namespace("acme"));
    private static final Scope OTHER = Scope.of(new Namespace("other"));

    @Test
    void strictFailsWhenNothingIsBound() {
        ThreadBoundScope scope = ThreadBoundScope.strict();

        assertThatThrownBy(scope::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No scope is bound");
    }

    @Test
    void fallbackAnswersWhenNothingIsBound() {
        ThreadBoundScope scope = ThreadBoundScope.withFallback(Scope.local());

        assertThat(scope.get()).isEqualTo(Scope.local());
        assertThat(scope.namespace()).isEqualTo(Namespace.DEFAULT);
        assertThat(scope.isBound()).isFalse();
    }

    @Test
    void runInBindsForTheBodyAndRestoresAfter() {
        ThreadBoundScope scope = ThreadBoundScope.strict();

        Scope seen = scope.runIn(ACME, () -> {
            assertThat(scope.isBound()).isTrue();
            return scope.get();
        });

        assertThat(seen).isEqualTo(ACME);
        assertThat(scope.isBound()).isFalse();
    }

    @Test
    void nestedRunInRestoresTheOuterBinding() {
        ThreadBoundScope scope = ThreadBoundScope.strict();

        scope.runIn(ACME, () -> {
            scope.runIn(OTHER, () -> assertThat(scope.get()).isEqualTo(OTHER));
            assertThat(scope.get()).isEqualTo(ACME);
        });
    }

    @Test
    void bindingIsRestoredEvenWhenTheBodyThrows() {
        ThreadBoundScope scope = ThreadBoundScope.withFallback(Scope.local());

        assertThatThrownBy(() -> scope.runIn(ACME, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(scope.get()).isEqualTo(Scope.local());
    }

    @Test
    void anotherThreadStartsUnbound() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        AtomicReference<Boolean> boundOnOtherThread = new AtomicReference<>();

        scope.runIn(ACME, () -> join(Thread.ofVirtual().start(() -> boundOnOtherThread.set(scope.isBound()))));

        assertThat(boundOnOtherThread.get()).isFalse();
    }

    @Test
    void wrapCarriesTheBindingAcrossAThreadHop() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        AtomicReference<Scope> seenOnOtherThread = new AtomicReference<>();

        scope.runIn(ACME, () -> join(Thread.ofVirtual().start(scope.wrap(() -> seenOnOtherThread.set(scope.get())))));

        assertThat(seenOnOtherThread.get()).isEqualTo(ACME);
    }

    @Test
    void callInLetsCheckedExceptionsThrough() {
        ThreadBoundScope scope = ThreadBoundScope.strict();

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
}
