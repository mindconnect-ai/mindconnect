package ai.mindconnect.agent.voice;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.voice.protocol.VoiceEvent;
import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One exchange: what the caller said (audio or text) goes to the agent, the
 * answer streams back as tokens, and each finished sentence is spoken while
 * the agent writes on. The tokens arrive on the runtime's thread; speaking
 * happens on the turn's own, so a slow speech call never holds up the agent.
 */
final class VoiceTurn implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(VoiceTurn.class);
    /** Put into the sentence queue once the agent is done; the speaker stops there. */
    private static final String END = "\u0000end";

    /** What the caller said: recorded audio, or typed text. */
    record Input(byte[] audio, String filename, String contentType, String text) {

        static Input ofAudio(byte[] audio, String format) {
            String extension = format == null || format.isBlank() ? "webm" : format;
            return new Input(audio, "speech." + extension, "audio/" + extension, null);
        }

        static Input ofText(String text) {
            return new Input(null, null, null, text);
        }
    }

    private final VoicePorts ports;
    private final SessionId sessionId;
    private final Input input;
    private final String language;
    private final String voice;
    private final boolean speak;
    private final VoiceOutput output;
    private final SentenceSplitter splitter = new SentenceSplitter();
    private final BlockingQueue<String> sentences = new LinkedBlockingQueue<>();
    private volatile ChatTurnHandle handle;
    private volatile boolean cancelled;
    private int seq;

    VoiceTurn(VoicePorts ports, SessionId sessionId, Input input, String language, String voice, boolean speak,
              VoiceOutput output) {
        this.ports = ports;
        this.sessionId = sessionId;
        this.input = input;
        this.language = language;
        this.voice = voice;
        this.speak = speak;
        this.output = output;
    }

    @Override
    public void run() {
        try {
            String message = input.text() != null ? input.text() : transcribe();
            if (cancelled) return;
            if (message == null || message.isBlank()) {
                output.send(new VoiceEvent.Error("nothing-heard", "Nothing was heard in the recording"));
                output.send(new VoiceEvent.TurnDone());
                return;
            }
            handle = ports.chat().sendChat(sessionId, message, this::onEvent);
            handle.outcome().whenComplete((result, error) -> sentences.add(END));
            speakSentences();
            if (cancelled) return;
            finish(handle.outcome().join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (cancelled) return;
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            log.warn("Voice turn in session {} failed: {}", sessionId, cause.getMessage(), cause);
            output.send(new VoiceEvent.Error("turn-failed", cause.getMessage()));
            output.send(new VoiceEvent.TurnDone());
        }
    }

    /** Stops the agent and the speaking; nothing more goes out for this turn. */
    void cancel() {
        cancelled = true;
        ChatTurnHandle running = handle;
        if (running != null) running.cancel();
        sentences.add(END);
    }

    private String transcribe() {
        var request = new TranscriptionRequest(input.audio(), input.filename(), input.contentType(), language, null);
        String text = ports.transcription().transcribe(ports.settings().speechToTextConfig(), request).text();
        if (!cancelled && text != null && !text.isBlank()) {
            output.send(new VoiceEvent.Transcript(text.strip()));
        }
        return text;
    }

    private void onEvent(StreamEvent event) {
        if (cancelled || !(event instanceof StreamEvent.Token token)) return;
        output.send(new VoiceEvent.Token(token.text()));
        if (speak) sentences.addAll(splitter.add(token.text()));
    }

    private void speakSentences() throws InterruptedException {
        if (!speak) {
            sentences.take();
            return;
        }
        for (String sentence = sentences.take(); !END.equals(sentence) && !cancelled; sentence = sentences.take()) {
            speakOne(sentence);
        }
        if (!cancelled) speakOne(splitter.flush());
    }

    private void speakOne(String sentence) {
        String text = SpokenTextFilter.spoken(sentence);
        if (text.isEmpty()) return;
        SpeechResult speech = ports.speech().synthesize(ports.settings().textToSpeechConfig(),
                new SpeechRequest(text, voice, null));
        if (cancelled) return;
        output.send(new VoiceEvent.Speech(++seq, text, speech.contentType()));
        output.sendAudio(speech.audio());
    }

    private void finish(TurnResult result) {
        if (result.isIncomplete()) {
            output.send(new VoiceEvent.Error("approval-required",
                    "The agent wants to run a tool that needs approval, which a voice conversation cannot give"));
        } else {
            output.send(new VoiceEvent.Reply(result.text()));
        }
        output.send(new VoiceEvent.TurnDone());
    }
}
