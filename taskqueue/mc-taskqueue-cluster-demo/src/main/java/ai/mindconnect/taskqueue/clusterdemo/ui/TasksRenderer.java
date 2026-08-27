package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import ai.mindconnect.ui.ext.jsonviewer.UiJsonViewer;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiScrollPane;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTree;
import ai.mindconnect.ui.model.UiTreeNode;
import ai.mindconnect.ui.model.UiTrigger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the live board — used by the page GET and by every SSE patch, so
 * both always show the same thing. All panels are REPLACE targets: replays
 * and drop-oldest gaps repair themselves on the next event.
 *
 * <p>Tasks render as a TREE (parent/child is the queue's own relation);
 * clicking a node patches the detail panel next to it. The selection lives
 * here so the stream keeps the panel live — process-wide on purpose (a demo
 * has one viewer; per-client selection would need per-client channels).
 *
 * <p>The event feed comes from a bounded deque filled by ONE permanent channel
 * subscription (per-connection consumers would double-log with two tabs open).
 */
@Component
public class TasksRenderer {

    public static final String BOARD_ID = "task-board";

    private static final int MAX_ROOTS = 50;
    private static final int MAX_FEED_LINES = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TaskStore store;
    private final ChannelRegistry channels;
    private final ObjectMapper objectMapper;
    private final Deque<String> feed = new ArrayDeque<>();
    private final Set<String> channelIds = ConcurrentHashMap.newKeySet();
    private volatile String selectedTaskId;

    /** Channel inspector: one live subscription fills a bounded message log. */
    private final Deque<String> channelLog = new ArrayDeque<>();
    private volatile String selectedChannelId;
    private volatile long channelLogSeq;
    private Subscription channelLogSubscription;

    public TasksRenderer(TaskStore store, ChannelRegistry channels, ObjectMapper objectMapper) {
        this.store = store;
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void attach() {
        channels.addListener(new ai.mindconnect.channel.ChannelLifecycleListener() {
            @Override public void onMaterialized(String channelId) { channelIds.add(channelId); }
            @Override public void onEvicted(String channelId) { channelIds.remove(channelId); }
        });
        Channel<TaskEvent> channel = channels.channel(TaskChannelBridge.ALL_TASKS);
        channelIds.add(TaskChannelBridge.ALL_TASKS);
        channel.subscribe(0, event -> addFeedLine(event.seq(), event.value()));
    }

    public void select(String taskId) {
        this.selectedTaskId = taskId;
    }

    public String selectedTaskId() {
        return selectedTaskId;
    }

    /**
     * Opens the inspector on a channel: replay the buffered tail
     * ({@code subscribe(0)} delivers the ring buffer, then continues live)
     * into the bounded message log. Passing an unknown id closes the inspector.
     */
    public synchronized void selectChannel(String channelId) {
        if (channelLogSubscription != null) {
            channelLogSubscription.close();
            channelLogSubscription = null;
        }
        synchronized (channelLog) {
            channelLog.clear();
        }
        this.selectedChannelId = channels.find(channelId).isPresent() ? channelId : null;
        if (this.selectedChannelId != null) {
            Channel<Object> channel = channels.channel(channelId);
            channelLogSeq = 0;
            channelLogSubscription = channel.subscribe(0,
                    event -> addChannelLogLine(event.seq(), event.value()));
            // The buffered-tail replay runs on the drain thread; give it a
            // moment so the patch answering this click already shows history.
            long target = channel.lastSeq();
            long deadline = System.currentTimeMillis() + 500;
            while (channelLogSeq < target && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public UiPage boardPage() {
        return UiPage.of("/tasks", board());
    }

    public UiPatch patchFor(Channel.Event<TaskEvent> event) {
        return selectionPatch()
                .patch(UiPatch.Operation.replace("event-feed", feedStack()))
                .patch(UiPatch.Operation.replace("channel-panel", channelPanel()))
                .patch(UiPatch.Operation.replace("channel-inspector", channelInspector()));
    }

    /** Channels table + inspector — what changes when a channel is picked. */
    public UiPatch channelSelectionPatch() {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("channel-panel", channelPanel()))
                .patch(UiPatch.Operation.replace("channel-inspector", channelInspector()));
    }

    /** Tree + detail panel — what changes when the selection changes. */
    public UiPatch selectionPatch() {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("task-tree", taskTree()))
                .patch(UiPatch.Operation.replace("task-detail-panel", detailPanel()));
    }

    private UiNode board() {
        // Tree and detail carry the page; feed, channels and inspector move
        // behind tabs so they stop shouting. The tab SECTION is never a patch
        // target — only its inner panels are replaced, so the picked tab
        // survives every live update.
        var activity = UiSection.of("activity", null)
                .section("activity-events", "Events",
                        UiScrollPane.of("event-feed-pane", feedStack())
                                .maxHeight("300px")
                                .stickToLatest(true))
                .section("activity-channels", "Channels",
                        UiStack.of("channels-tab")
                                .child(channelPanel())
                                .child(UiScrollPane.of("channel-inspector-pane", channelInspector())
                                        .maxHeight("260px")
                                        .stickToLatest(true)));
        return UiStack.of(BOARD_ID)
                .child(UiStack.of("board-main")
                        .direction(UiStack.Direction.HORIZONTAL)
                        .gap(16)
                        .child(taskTree())
                        .child(detailPanel()))
                .child(activity)
                .child(UiForm.of("board-actions", null)
                        .action(UiAction.danger("clear-finished", "Clear finished tasks").icon("delete")
                                .confirm("Forget all finished task trees? Running tasks stay.")
                                .dispatch("POST", "/tasks/purge")));
    }

    UiNode taskTree() {
        var tree = UiTree.of("task-tree", "Tasks");
        List<TaskRecord> roots = allTasks().stream()
                .filter(t -> t.parentTaskId() == null)
                .sorted(Comparator.comparing(TaskRecord::submittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_ROOTS)
                .toList();
        if (roots.isEmpty()) {
            tree.node(UiTreeNode.of("tree-empty", "No tasks yet — submit one."));
        }
        for (TaskRecord root : roots) {
            tree.node(treeNode(root));
        }
        return tree;
    }

    private UiTreeNode treeNode(TaskRecord task) {
        var node = UiTreeNode.of(task.id(), treeLabel(task))
                .icon(statusIcon(task.status()))
                .open(true)
                .selected(task.id().equals(selectedTaskId))
                .onClick(UiTrigger.api("GET", "/tasks/" + task.id() + "/panel"));
        for (TaskRecord child : store.byParent(task.id())) {
            node.child(treeNode(child));
        }
        return node;
    }

    private String treeLabel(TaskRecord task) {
        var label = new StringBuilder()
                .append(taskTitle(task)).append(" ").append(shortId(task.id()))
                .append(" — ").append(task.status());
        String progress = progress(task);
        if (!progress.isEmpty()) {
            label.append("  ·  ").append(progress);
        }
        return label.toString();
    }

    /**
     * A task names itself: {@code state("title")} (the worker's own choice,
     * settable mid-run) wins over {@code payload("title")} (set at submit
     * time, e.g. the crawl giving every page its URL); the type is the
     * fallback.
     */
    public static String taskTitle(TaskRecord task) {
        Object fromState = task.state().get("title");
        if (fromState instanceof String s && !s.isBlank()) return s;
        Object fromPayload = task.payload().get("title");
        if (fromPayload instanceof String s && !s.isBlank()) return s;
        return task.type();
    }

    UiNode detailPanel() {
        var panel = UiStack.of("task-detail-panel");
        TaskRecord task = selectedTaskId == null ? null
                : store.find(selectedTaskId).orElse(null);
        if (task == null) {
            return panel.child(UiText.of("task-detail-hint", "Select a task to see its details."));
        }

        panel.child(UiText.of("task-detail-header",
                taskTitle(task) + " (" + task.type() + ") " + task.id()
                        + " — " + task.status() + " (attempt " + task.attempt()
                        + (task.nodeId() != null ? " on " + task.nodeId() : "") + ")"));

        if (!task.status().terminal()) {
            panel.child(UiForm.of("task-actions", null)
                    .action(UiAction.danger("cancel", "Cancel task").icon("delete")
                            .confirm("Cancel task " + task.id() + " and all of its children?")
                            .dispatch("POST", "/tasks/" + task.id() + "/cancel")));
        }

        panel.child(UiJsonViewer.of("task-payload", json(task.payload())).expandLevel(2));
        if (!task.state().isEmpty()) {
            panel.child(UiJsonViewer.of("task-state", json(task.state())).expandLevel(2));
        }
        if (task.result() != null) {
            panel.child(UiText.of("task-result", "Result: " + task.result()));
        }
        if (task.failure() != null) {
            panel.child(UiJsonViewer.of("task-failure", json(task.failure())).expandLevel(1));
        }
        return panel;
    }

    UiNode feedStack() {
        var stack = UiStack.of("event-feed").gap(0);
        List<String> lines;
        synchronized (feed) {
            lines = new ArrayList<>(feed);
        }
        if (lines.isEmpty()) {
            stack.child(UiText.of("feed-empty", "No events yet — submit a task."));
        }
        int i = 0;
        for (String line : lines) {
            stack.child(UiText.of("feed-" + i++, line));
        }
        return stack;
    }

    UiNode channelPanel() {
        var table = UiTable.of("channel-panel", "Channels")
                .column(UiColumn.text("channel", "Channel"))
                .column(UiColumn.number("lastSeq", "Last seq"))
                .column(UiColumn.number("subscribers", "Subscribers"))
                .column(UiColumn.text("idle", "Idle"))
                .rowAction(UiAction.secondary("inspect", "Inspect").icon("show")
                        .dispatch("GET", "/tasks/channels/{id}/panel"));
        if (selectedChannelId != null) {
            table.selectedRow(selectedChannelId);
        }
        for (String id : channelIds.stream().sorted().toList()) {
            channels.find(id).ifPresent(ch -> {
                var channel = (Channel<?>) ch;
                long idleMs = Math.max(0, System.currentTimeMillis() - channel.lastActivityMs());
                table.row(Map.of(
                        "id", id,
                        "channel", id,
                        "lastSeq", channel.lastSeq(),
                        "subscribers", channel.subscriberCount(),
                        "idle", (idleMs / 1000) + "s"));
            });
        }
        return table;
    }

    /** The messages of the inspected channel, seq-stamped, live. */
    UiNode channelInspector() {
        var stack = UiStack.of("channel-inspector").gap(0);
        if (selectedChannelId == null) {
            return stack.child(UiText.of("channel-inspector-hint",
                    "Inspect a channel to see its messages live."));
        }
        stack.child(UiText.of("channel-inspector-title",
                "Channel \"" + selectedChannelId + "\" — buffered tail + live"));
        List<String> lines;
        synchronized (channelLog) {
            lines = new ArrayList<>(channelLog);
        }
        if (lines.isEmpty()) {
            stack.child(UiText.of("channel-msg-empty", "No messages yet."));
        }
        int i = 0;
        for (String line : lines) {
            stack.child(UiText.of("channel-msg-" + i++, line));
        }
        return stack;
    }

    private void addChannelLogLine(long seq, Object value) {
        String message;
        try {
            message = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            message = String.valueOf(value);
        }
        String line = String.format("%6d  %s", seq, abbreviate(message, 160));
        synchronized (channelLog) {
            channelLog.addLast(line);
            while (channelLog.size() > MAX_FEED_LINES) {
                channelLog.removeFirst();
            }
        }
        channelLogSeq = seq;
    }

    private List<TaskRecord> allTasks() {
        var all = new ArrayList<TaskRecord>();
        for (TaskStatus status : TaskStatus.values()) {
            all.addAll(store.byStatus(status, Integer.MAX_VALUE));
        }
        return all;
    }

    /** Scalar state entries as a compact "k=v" summary; lists shown as counts. */
    public static String progress(TaskRecord task) {
        if (task.status().terminal()) {
            if (task.failure() != null) return abbreviate(task.failure().message(), 60);
            return abbreviate(task.result(), 60);
        }
        var parts = new ArrayList<String>();
        task.state().forEach((key, value) -> {
            if (value instanceof Number || value instanceof Boolean) {
                parts.add(key + "=" + value);
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                parts.add(key + "=" + list.size());
            }
        });
        return abbreviate(String.join(", ", parts), 60);
    }

    private static String statusIcon(TaskStatus status) {
        return switch (status) {
            case QUEUED -> "clock";
            case RUNNING -> "play";
            case SUSPENDED -> "pause";
            case COMPLETED -> "check";
            case FAILED -> "error";
            case CANCELLED -> "cancel";
        };
    }

    private void addFeedLine(long seq, TaskEvent event) {
        TaskRecord task = event.task();
        var line = new StringBuilder()
                .append(time(Instant.now())).append("  ")
                .append(String.format("%-9s", event.type())).append("  ")
                .append(task.type()).append(" ").append(shortId(task.id()));
        if (event.type() == TaskEvent.Type.STATE) {
            line.append("  ").append(progress(task));
        }
        if (event.type() == TaskEvent.Type.TERMINAL) {
            line.append("  ").append(task.status());
            if (task.failure() != null) {
                line.append(": ").append(abbreviate(task.failure().message(), 80));
            } else if (task.result() != null) {
                line.append(": ").append(abbreviate(task.result(), 80));
            }
        }
        synchronized (feed) {
            feed.addLast(line.toString());
            while (feed.size() > MAX_FEED_LINES) {
                feed.removeFirst();
            }
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"<unserializable: " + e.getMessage() + ">\"";
        }
    }

    public static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String time(Instant instant) {
        if (instant == null) return "";
        return LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(TIME);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
