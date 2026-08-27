package ai.mindconnect.taskqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the queue's narrative — attach it, log nothing yourself.
 *
 * <p>Everything a turn does shows up here without a single statement inside
 * the core: workers write their progress into the task state
 * ({@code phase}, {@code round}, {@code tool}), and {@code onStateChanged}
 * turns that into a line. Lifecycle at INFO, progress at DEBUG, failures at
 * WARN.
 *
 * <pre>
 * queue.addListener(new LoggingTaskListener());
 * </pre>
 *
 * <p>What listeners canNOT see are failures a method swallows internally —
 * those few places log directly (a broken subscriber, a publish that failed,
 * a fallback that kicked in).
 */
public final class LoggingTaskListener implements TaskListener {

    private final Logger log;

    public LoggingTaskListener() {
        this(LoggerFactory.getLogger("ai.mindconnect.taskqueue.tasks"));
    }

    public LoggingTaskListener(Logger log) {
        this.log = log;
    }

    @Override
    public void onSubmitted(TaskRecord task) {
        log.debug("submitted {} [{}] priority={} parent={}",
                task.id(), task.type(), task.priority(), task.parentTaskId());
    }

    @Override
    public void onStarted(TaskRecord task) {
        log.info("started {} [{}] attempt={}", task.id(), task.type(), task.attempt());
    }

    @Override
    public void onStateChanged(TaskRecord task) {
        log.debug("progress {} [{}] {}", task.id(), task.type(), task.state());
    }

    @Override
    public void onSuspended(TaskRecord task) {
        log.info("suspended {} [{}] waiting for {}", task.id(), task.type(), task.waitingFor());
    }

    @Override
    public void onWoken(TaskRecord task) {
        log.info("woken {} [{}]", task.id(), task.type());
    }

    @Override
    public void onTerminal(TaskRecord task) {
        long tookMs = task.startedAt() == null || task.endedAt() == null
                ? -1 : task.endedAt().toEpochMilli() - task.startedAt().toEpochMilli();
        switch (task.status()) {
            case FAILED -> log.warn("failed {} [{}] after {}ms: {}",
                    task.id(), task.type(), tookMs,
                    task.failure() != null ? task.failure().message() : null);
            case CANCELLED -> log.info("cancelled {} [{}] after {}ms", task.id(), task.type(), tookMs);
            default -> log.info("completed {} [{}] in {}ms result={}",
                    task.id(), task.type(), tookMs, task.result());
        }
    }
}
