package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.chatui.ui.component.ChatFormComponent;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ai.mindconnect.agent.SessionId;

/**
 * Speaking into the chat instead of typing. The composer's microphone records
 * in the browser and posts the recording here; the answer is the composer's
 * textarea with the transcript in it, so the words land where typed ones do
 * and are sent — or corrected first — by the person who spoke them. Nothing
 * is submitted on their behalf.
 *
 * <p>The model is the LLM config named {@value #CONFIG_NAME}. That is a name,
 * not a model: point an alias at whichever speech-to-text config should serve
 * the chat and this endpoint follows it, the same way an agent's chat model is
 * chosen.
 */
@RestController
@RequestMapping("/chat/api/sessions/{sessionId}/voice")
public class ChatVoiceUiController {

    /**
     * The LLM config the chat dictates with — the same name the transcription
     * API defaults to, defined once so a rename cannot leave the two apart.
     * An alias of this name is followed.
     */
    public static final String CONFIG_NAME =
            ai.mindconnect.agentrest.service.TranscriptionJobService.DEFAULT_CONFIG_NAME;

    private static final Logger log = LoggerFactory.getLogger(ChatVoiceUiController.class);

    private final ObjectProvider<LlmTranscription> transcription;
    private final AgentSessionRepository sessions;
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;

    public ChatVoiceUiController(ObjectProvider<LlmTranscription> transcription,
                                 AgentSessionRepository sessions,
                                 ai.mindconnect.chatui.service.ActiveStreams activeStreams) {
        this.transcription = transcription;
        this.sessions = sessions;
        this.activeStreams = activeStreams;
    }

    /**
     * Turns the posted recording into text and hands back the composer with
     * it. Anything already typed arrives as {@code message} and keeps its
     * place in front of the transcript, so dictating after typing adds rather
     * than replaces.
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UiPatch> transcribe(@PathVariable("sessionId") String sessionIdValue,
                                              @RequestParam("audio") MultipartFile audio,
                                              @RequestParam(value = "message", required = false) String typed,
                                              @AuthenticationPrincipal OidcUser user) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        // Someone else's session is not found here, exactly as it is not found
        // anywhere else in the chat — dictation is no way around that.
        if (!ownsSession(sessionId, user)) {
            return ResponseEntity.notFound().build();
        }
        LlmTranscription speech = transcription.getIfAvailable();
        if (speech == null) {
            return ResponseEntity.ok(toast(UiToast.error(
                    "This server has no speech-to-text gateway.").title("Dictation unavailable")));
        }
        if (audio.isEmpty()) {
            return ResponseEntity.ok(toast(UiToast.error(
                    "The recording was empty.").title("Nothing to transcribe")));
        }

        String filename = audio.getOriginalFilename() == null ? "recording.webm" : audio.getOriginalFilename();
        TranscriptionResult result;
        try {
            result = speech.transcribe(CONFIG_NAME, TranscriptionRequest.of(
                    audio.getBytes(), filename, audio.getContentType()));
        } catch (Exception e) {
            log.warn("Dictation in session {} failed: {}", sessionId, e.toString());
            return ResponseEntity.ok(toast(UiToast.error(reason(e)).title("Dictation failed")));
        }

        String spoken = result.text() == null ? "" : result.text().strip();
        if (spoken.isEmpty()) {
            return ResponseEntity.ok(toast(UiToast.error(
                    "The model heard nothing in the recording.").title("Nothing recognised")));
        }
        log.info("Dictated {} characters into session {}", spoken.length(), sessionId);
        // While a turn streams the composer is a status row: no textarea, so
        // the patch below would land nowhere and the words would be gone.
        // The toast keeps them readable until the turn ends.
        if (isStreaming(sessionId)) {
            return ResponseEntity.ok(toast(UiToast.info(spoken)
                    .title("Heard while the agent was answering")
                    // Sticky: the words are the point, and they are gone once
                    // the toast is.
                    .sticky()));
        }
        return ResponseEntity.ok(UiPatch.of().patch(UiPatch.Operation.replace(
                "message", ChatFormComponent.messageField(join(typed, spoken)))));
    }

    /** The session belongs to whoever is asking — the chat's own rule. */
    private boolean ownsSession(SessionId sessionId, OidcUser user) {
        return sessions.findById(sessionId)
                .filter(session -> ai.mindconnect.chatui.service.SessionOwnership.owns(session, user))
                .isPresent();
    }

    /**
     * Is a turn of this session streaming right now? The same question the
     * chat page asks to decide between the composer and the status row, asked
     * of the same registry.
     */
    private boolean isStreaming(SessionId sessionId) {
        return activeStreams.findHandle(ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId)).isPresent();
    }

    /** What was typed, then what was said — with one space between them. */
    private static String join(String typed, String spoken) {
        if (typed == null || typed.isBlank()) return spoken;
        String head = typed.stripTrailing();
        return head.isEmpty() ? spoken : head + " " + spoken;
    }

    /**
     * The failure in one line for a toast. A missing config is the likely one
     * — nobody has set up {@value #CONFIG_NAME} yet — and it deserves a
     * sentence that says what to do rather than a class name.
     */
    private static String reason(Exception e) {
        // Which failure it is decides the sentence, not what the message
        // happens to contain: a config of the wrong type mentions the name
        // too, and telling its owner to create one they already have sends
        // them the wrong way.
        if (e instanceof ai.mindconnect.common.DomainException) {
            return "No LLM config named '" + CONFIG_NAME + "'. Create one of type "
                    + "Speech to text in the admin UI, or point an alias of that name at one.";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static UiPatch toast(UiToast toast) {
        return UiPatch.of().toast(toast);
    }
}
