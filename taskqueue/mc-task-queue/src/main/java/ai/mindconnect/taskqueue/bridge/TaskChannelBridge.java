package ai.mindconnect.taskqueue.bridge;

import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskRecord;

import java.util.function.Function;

/**
 * Publishes queue events onto a {@link ai.mindconnect.channel.Channel} so
 * admin tools and CLIs get a live task view.
 *
 * <p>Listener AND channel, on purpose: the listener is the source (it sees
 * every transition, in order, on the transition's thread), the channel is the
 * delivery (0..n subscribers, replay for late joiners via {@code afterSeq},
 * and a bounded per-subscriber queue so a slow viewer can never slow the
 * queue down). Publishing never blocks, and a listener exception can not
 * break the queue — the two properties that make this safe to attach.
 *
 * <p>This is the ONE place that knows both packages; {@code taskqueue} and
 * {@code channel} stay independent, and splitting them later moves this
 * bridge along with the channel.
 */
public final class TaskChannelBridge implements TaskListener {

    /** Default channel: every task of this queue on one stream. */
    public static final String ALL_TASKS = "tasks";

    private final ChannelRegistry channels;
    private final Function<TaskRecord, String> route;

    /** All tasks on the {@link #ALL_TASKS} channel — the task-manager view. */
    public static TaskChannelBridge global(ChannelRegistry channels) {
        return new TaskChannelBridge(channels, task -> ALL_TASKS);
    }

    /**
     * Routes each event to a channel of your choosing — e.g. by task type or
     * by root, so a client can watch one run instead of the whole queue.
     */
    public static TaskChannelBridge routed(ChannelRegistry channels,
                                           Function<TaskRecord, String> route) {
        return new TaskChannelBridge(channels, route);
    }

    private TaskChannelBridge(ChannelRegistry channels, Function<TaskRecord, String> route) {
        this.channels = channels;
        this.route = route;
    }

    @Override public void onSubmitted(TaskRecord task) { publish(TaskEvent.Type.SUBMITTED, task); }

    @Override public void onStarted(TaskRecord task) { publish(TaskEvent.Type.STARTED, task); }

    @Override public void onStateChanged(TaskRecord task) { publish(TaskEvent.Type.STATE, task); }

    @Override public void onSuspended(TaskRecord task) { publish(TaskEvent.Type.SUSPENDED, task); }

    @Override public void onWoken(TaskRecord task) { publish(TaskEvent.Type.WOKEN, task); }

    @Override public void onTerminal(TaskRecord task) { publish(TaskEvent.Type.TERMINAL, task); }

    private void publish(TaskEvent.Type type, TaskRecord task) {
        channels.channel(route.apply(task)).publish(new TaskEvent(type, task));
    }
}
