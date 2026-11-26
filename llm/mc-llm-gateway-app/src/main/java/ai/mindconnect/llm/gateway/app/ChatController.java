package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.LlmResponse;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/** The runtime API over HTTP: a client names the namespace and the config, nothing else. */
@RestController
@RequestMapping("/api/ns/{namespace}/chat")
@Tag(name = "Chat", description = "Call a model by the name of its config")
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmChat chat;
    private final ThreadBoundScopeSupplier scope;

    ChatController(LlmChat chat, ThreadBoundScopeSupplier scope) {
        this.chat = chat;
        this.scope = scope;
    }

    @Operation(summary = "Chat and wait for the whole answer")
    @PostMapping
    LlmResponse chat(@PathVariable String namespace, @RequestBody ChatRequest request) {
        return chat.chat(request.toLlmRequest(false));
    }

    /**
     * Streams the answer as server-sent events, one per chunk, named after the
     * chunk's type ({@code TextDelta}, {@code ToolCallDelta}, {@code ThinkingDelta},
     * {@code Done}). A client that goes away cancels the provider call.
     */
    @Operation(summary = "Chat and stream the answer as server-sent events")
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String namespace, @RequestBody ChatRequest request) {
        var llmRequest = request.toLlmRequest(true);
        Scope bound = scope.get();
        SseEmitter emitter = new SseEmitter(0L);
        Cancellation cancellation = Cancellation.none();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(error -> cancellation.cancel());
        Thread.ofVirtual().name("llm-stream-" + namespace).start(() -> scope.runIn(bound, () -> {
            try {
                chat.chatStreaming(llmRequest, chunk -> send(emitter, chunk), cancellation);
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Streaming chat on '{}' failed: {}", llmRequest.configName(), e.getMessage());
                emitter.completeWithError(e);
            }
        }));
        return emitter;
    }

    private static void send(SseEmitter emitter, LlmStreamChunk chunk) {
        try {
            emitter.send(SseEmitter.event().name(chunk.getClass().getSimpleName()).data(chunk, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("The client went away", e);
        }
    }
}
