package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.memory.InMemorySharedLockStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock's whole job is to survive a holder that never comes back, so the
 * interesting cases are all about the lease — driven by a steerable clock so
 * nothing here waits.
 */
class SharedLockStoreTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    private static final Duration LEASE = Duration.ofSeconds(30);

    private TestClock clock;
    private SharedLockStore locks;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        locks = new InMemorySharedLockStore(clock);
    }

    @Test
    void onlyOneHolderAtATime() {
        assertThat(locks.acquire("crawl-1", "out-dir", LEASE)).isPresent();
        assertThat(locks.acquire("crawl-1", "out-dir", LEASE)).isEmpty();
    }

    @Test
    void aDeadHolderLosesTheLockWhenItsLeaseRunsOut() {
        String token = locks.acquire("crawl-1", "out-dir", LEASE).orElseThrow();

        clock.advance(Duration.ofSeconds(31));               // the holder never came back

        assertThat(locks.acquire("crawl-1", "out-dir", LEASE)).isPresent();
        // and it must learn that it is no longer the holder
        assertThat(locks.renew("crawl-1", "out-dir", token, LEASE)).isFalse();
    }

    @Test
    void renewingKeepsTheLockPastTheOriginalLease() {
        String token = locks.acquire("crawl-1", "out-dir", LEASE).orElseThrow();

        clock.advance(Duration.ofSeconds(20));
        assertThat(locks.renew("crawl-1", "out-dir", token, LEASE)).isTrue();
        clock.advance(Duration.ofSeconds(20));               // past the FIRST lease

        assertThat(locks.acquire("crawl-1", "out-dir", LEASE)).isEmpty();
    }

    @Test
    void aLateHolderCannotReleaseTheLockSomeoneElseNowOwns() {
        String stale = locks.acquire("crawl-1", "out-dir", LEASE).orElseThrow();
        clock.advance(Duration.ofSeconds(31));
        String fresh = locks.acquire("crawl-1", "out-dir", LEASE).orElseThrow();

        assertThat(locks.release("crawl-1", "out-dir", stale)).isFalse();
        assertThat(locks.heldUntil("crawl-1", "out-dir")).isPresent();   // still held

        assertThat(locks.release("crawl-1", "out-dir", fresh)).isTrue();
        assertThat(locks.heldUntil("crawl-1", "out-dir")).isEmpty();
    }

    @Test
    void keysAndIdsDoNotCollide() {
        assertThat(locks.acquire("crawl-1", "out-dir", LEASE)).isPresent();
        assertThat(locks.acquire("crawl-1", "other-dir", LEASE)).isPresent();
        assertThat(locks.acquire("crawl-2", "out-dir", LEASE)).isPresent();
    }

    @Test
    void releasingFreesItImmediately() {
        String token = locks.acquire("crawl-1", "out-dir", LEASE).orElseThrow();
        assertThat(locks.release("crawl-1", "out-dir", token)).isTrue();

        Optional<String> next = locks.acquire("crawl-1", "out-dir", LEASE);
        assertThat(next).isPresent();
        assertThat(next.orElseThrow()).isNotEqualTo(token);
    }
}
