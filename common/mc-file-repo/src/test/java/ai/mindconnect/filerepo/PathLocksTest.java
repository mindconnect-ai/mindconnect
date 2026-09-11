package ai.mindconnect.filerepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathLocksTest {

    private static final Duration SHORT = Duration.ofMillis(100);
    private static final Duration LONG = Duration.ofMinutes(1);

    @TempDir
    Path dir;

    @Test
    void aSecondLockOnTheSameThreadIsRefusedAndTheFirstOneReleased() throws Exception {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");

        assertThatThrownBy(() -> PathLocks.withLock(a, LONG, () -> PathLocks.withLock(b, LONG, () -> "never")))
                .isInstanceOf(NestedWriteException.class)
                .hasMessageContaining("a.json")
                .hasMessageContaining("b.json");

        assertThat(PathLocks.isHeldByCurrentThread()).isFalse();
        assertThat(onOtherThread(() -> PathLocks.withLock(a, SHORT, () -> "a free"))).isEqualTo("a free");
        assertThat(onOtherThread(() -> PathLocks.withLock(b, SHORT, () -> "b free"))).isEqualTo("b free");
    }

    @Test
    void theSameFileTwiceIsRefusedToo() {
        Path a = dir.resolve("a.json");

        assertThatThrownBy(() -> PathLocks.withLock(a, LONG, () -> PathLocks.withLock(a, LONG, () -> "never")))
                .isInstanceOf(NestedWriteException.class);
    }

    @Test
    void aLockHeldTooLongTimesOutAndNamesTheHolder() throws Exception {
        Path file = dir.resolve("slow.json");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().name("slow-writer").start(() -> holdUntil(file, holding, release));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> PathLocks.withLock(file, SHORT, () -> "never"))
                    .isInstanceOf(LockTimeoutException.class)
                    .hasMessageContaining("slow.json")
                    .hasMessageContaining("slow-writer");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void writersOfDifferentFilesDoNotWaitForEachOther() throws Exception {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        for (int i = 0; PathLocks.stripeOf(b) == PathLocks.stripeOf(a); i++) {
            b = dir.resolve("b" + i + ".json");
        }
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> holdUntil(a, holding, release));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(PathLocks.withLock(b, SHORT, () -> "written")).isEqualTo("written");
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void writersOfOneFileTakeTurns() throws Exception {
        Path file = dir.resolve("counter.json");
        int[] counter = {0};

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(pool.submit(() -> PathLocks.withLock(file, LONG, () -> {
                    int seen = counter[0];
                    Thread.yield();
                    counter[0] = seen + 1;
                    return null;
                })));
            }
            for (Future<Object> future : futures) future.get();
        }

        assertThat(counter[0]).isEqualTo(1000);
    }

    private static void holdUntil(Path file, CountDownLatch holding, CountDownLatch release) {
        try {
            PathLocks.withLock(file, LONG, () -> {
                holding.countDown();
                awaitQuietly(release);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T onOtherThread(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().start(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
