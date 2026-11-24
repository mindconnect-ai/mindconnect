package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.LlmConfigTestComponent;
import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPatch;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The "Test config" dialog: one message, text to embed or recording sent
 * through a config, and the answer shown under the form to send again.
 */
@RestController
@RequestMapping("/admin/api/llm-configs")
public class LlmConfigTestUiController {

    private final LlmConfigRepository repository;
    private final LlmConfigTestService testService;

    public LlmConfigTestUiController(LlmConfigRepository repository, LlmConfigTestService testService) {
        this.repository = repository;
        this.testService = testService;
    }

    /**
     * Opens the "Test config" dialog as a patch over whatever is on screen —
     * the page underneath is untouched. Empty body / no result yet; the
     * admin types a message (chat) or a text to embed (embedding config)
     * and submits via the form's POST.
     */
    @GetMapping("/{id}/test")
    public ResponseEntity<UiPatch> testDialog(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(testPatch(c, null, null)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Executes the test — one user-turn for a chat config, text → vector
     * for an embedding config — and re-renders the dialog in place with
     * the result underneath the form so the admin can re-send.
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<UiPatch> runTest(@PathVariable("id") String idValue,
                                           @RequestBody Map<String, Object> raw) {
        LlmConfigId id = LlmConfigId.of(idValue);
        var body = new FormBody(raw);
        String message = body.str("message");
        return repository.findById(id)
                .map(c -> {
                    LlmConfigTestService.Result result = testService.test(c, message);
                    return ResponseEntity.ok(testPatch(c, message, result));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Executes a speech-to-text test: the dropped recording goes to the
     * transcription endpoint and the dialog comes back with the transcript.
     * A separate endpoint because this one takes multipart, not a form body.
     */
    @PostMapping(value = "/{id}/test-audio",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UiPatch> runAudioTest(
            @PathVariable("id") String idValue,
            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> {
                    byte[] bytes;
                    try {
                        bytes = audio.getBytes();
                    } catch (java.io.IOException e) {
                        return ResponseEntity.ok(testPatch(c, null,
                                LlmConfigTestService.Result.error(
                                        "Could not read the upload: " + e.getMessage(), 0)));
                    }
                    String filename = audio.getOriginalFilename() == null
                            ? "recording.webm" : audio.getOriginalFilename();
                    LlmConfigTestService.Result result = testService.testTranscription(
                            c, bytes, filename, audio.getContentType());
                    return ResponseEntity.ok(testPatch(c, null, result));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * What a config under test really is. An alias carries no type of its
     * own — it points at another config by name, and the test follows that
     * name — so the type of the config behind it decides what the dialog
     * asks for.
     */
    private LlmConfigType effectiveType(LlmConfig config) {
        if (!config.isAlias()) return config.type();
        try {
            return repository.findResolvedByName(config.name())
                    .map(LlmConfig::type)
                    .orElse(config.type());
        } catch (RuntimeException e) {
            // A chain that is broken, circular or too deep throws. That is
            // worth seeing — but in the dialog, where the test reports it,
            // not as a 500 in place of the dialog.
            return config.type();
        }
    }

    /** Close is just "remove the overlay" — the page behind stays as-is. */
    @PostMapping("/test-dialog/close")
    public UiPatch closeTestDialog() {
        return UiPatch.of().patch(UiPatch.Operation.remove("llm-test-dialog"));
    }

    /**
     * The test dialog as a remove+append patch on the body-level dialog host
     * (same pattern as the tool-test dialogs): remove is a no-op on first
     * open and replaces the modal in place on a re-render; a null close-href
     * just removes the overlay without navigating. {@code message} preserves
     * the textarea content across a round-trip; {@code result} is the
     * outcome of the just-completed call (null for the initial open).
     */
    private UiPatch testPatch(LlmConfig c, String message, LlmConfigTestService.Result result) {
        var component = new LlmConfigTestComponent(c, effectiveType(c), message, result);
        UiDialog dialog = UiDialog.of(component.title(), null, component.render());
        dialog.setId("llm-test-dialog");
        return UiPatch.of()
                .patch(UiPatch.Operation.remove("llm-test-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }
}
