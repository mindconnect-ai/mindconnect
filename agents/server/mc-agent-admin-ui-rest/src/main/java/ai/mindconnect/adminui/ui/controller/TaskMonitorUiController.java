package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The task manager behind the header's task badge. The dialog opens and
 * closes as patches on the body-level dialog host, like About; while it is
 * open the stream every page carries ({@code /sse}) keeps it current, so
 * there is no refresh button and nothing to poll.
 */
@RestController
@RequestMapping(TaskMonitorComponent.OPEN_URL)
public class TaskMonitorUiController {

    private final TaskMonitor monitor;

    public TaskMonitorUiController(TaskMonitor monitor) {
        this.monitor = monitor;
    }

    /** Opens the dialog with the board as it is right now. */
    @GetMapping
    public UiPatch open(@AuthenticationPrincipal OidcUser user) {
        UiDialog dialog = UiDialog.of("Task queue", null,
                TaskMonitorComponent.body(monitor.snapshot(), userId(user)));
        dialog.setId(TaskMonitorComponent.DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(TaskMonitorComponent.DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    @PostMapping("/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(TaskMonitorComponent.DIALOG_ID));
    }

    /**
     * Cancels one task — the caller's own only. The board itself is not
     * re-sent here: the cancel is a queue transition, and the stream delivers
     * the resulting snapshot to every tab, this one included.
     */
    @PostMapping("/{taskId}/cancel")
    public UiPatch cancel(@PathVariable String taskId, @AuthenticationPrincipal OidcUser user) {
        TaskMonitor.CancelResult result = monitor.cancel(taskId, userId(user));
        UiToast toast = switch (result) {
            case CANCELLED -> UiToast.success("Cancel requested. A running task stops at its next checkpoint.");
            case NOT_OWNER -> UiToast.error("Only the user a task belongs to can cancel it.");
            case ALREADY_FINISHED -> UiToast.info("That task has already finished.");
            case UNKNOWN -> UiToast.error("No such task — it may have been cleared.");
        };
        return UiPatch.of().toast(toast);
    }

    /**
     * The live feed the SPA attaches to from every admin page (see
     * {@code AdminLayout}): one {@code patch} frame per change of the board,
     * rendered for the user on this connection. Live only — the page that
     * opened it already shows the current state.
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal OidcUser user) {
        SseEmitter emitter = new SseEmitter(0L);
        monitor.attach(emitter, userId(user));
        emitter.onCompletion(() -> monitor.detach(emitter));
        emitter.onError(t -> monitor.detach(emitter));
        emitter.onTimeout(() -> monitor.detach(emitter));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Sui-Stream-Channel", TaskMonitor.CHANNEL_ID);
        headers.add("Sui-Stream-Label", "Task queue");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    private static String userId(OidcUser user) {
        return user == null ? "mc_user" : user.getPreferredUsername();
    }
}
