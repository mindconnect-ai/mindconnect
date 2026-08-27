package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The live board: a task tree with a detail panel next to it. Clicking a tree
 * node patches the panel in place — there is no separate detail page. The
 * board page advertises the SSE stream via {@code activeStreams}, so a fresh
 * load or F5 re-attaches to the {@code task-board} channel on its own.
 */
@RestController
@RequestMapping("/tasks")
public class TasksController {

    private final TaskQueue queue;
    private final TaskStore store;
    private final TasksRenderer board;

    public TasksController(TaskQueue queue, TaskStore store, TasksRenderer board) {
        this.queue = queue;
        this.store = store;
        this.board = board;
    }

    @GetMapping
    public UiPage board() {
        return withStream(board.boardPage());
    }

    static UiPage withStream(UiPage page) {
        page.setActiveStreams(List.of(UiPage.ActiveStream.of(
                TasksRenderer.BOARD_ID, "/tasks/api/stream", "Task events", "/tasks")));
        return page;
    }

    /** Tree-node click: select the task and patch tree + detail panel. */
    @GetMapping("/{id}/panel")
    public UiPatch panel(@PathVariable String id) {
        board.select(queue.get(id).isPresent() ? id : null);
        return board.selectionPatch();
    }

    /** Channel-row click: open the inspector on that channel's messages. */
    @GetMapping("/channels/{id}/panel")
    public UiPatch channelPanel(@PathVariable String id) {
        board.selectChannel(id);
        return board.channelSelectionPatch();
    }

    /**
     * Clears the list: finished task trees are forgotten, running ones stay
     * (their records are still the truth about work in flight).
     */
    @PostMapping("/purge")
    public UiPatch purge() {
        int removed = store.purgeTerminal(Instant.now());
        if (board.selectedTaskId() != null && queue.get(board.selectedTaskId()).isEmpty()) {
            board.select(null);
        }
        return board.selectionPatch()
                .toast(removed == 0
                        ? UiToast.info("Nothing to clear — no finished task trees")
                        : UiToast.success("Cleared " + removed + " finished task"
                                + (removed == 1 ? "" : "s")));
    }

    @PostMapping("/{id}/cancel")
    public UiPatch cancel(@PathVariable String id) {
        boolean accepted = queue.cancel(id);
        return board.selectionPatch()
                .toast(accepted
                        ? UiToast.info("Cancel requested for " + id)
                        : UiToast.warn("Task " + id + " is already terminal"));
    }
}
