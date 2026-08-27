package ai.mindconnect.llm;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.*;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static ai.mindconnect.llm.domain.LlmProvider.LM_STUDIO;

import static org.assertj.core.api.Assertions.*;

class RoutingLlmChatServiceTest {

    private RoutingLlmChatService service;
    private LlmConfigRepository repo;
    private LlmConfig config;

    @BeforeEach
    void setUp() {
        Map<UUID, LlmConfig> store = new ConcurrentHashMap<>();
        repo = new LlmConfigRepository() {
            @Override public void save(LlmConfig c) { store.put(c.id(), c); }
            @Override public Optional<LlmConfig> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
            @Override public Optional<LlmConfig> findByName(String name) { return store.values().stream().filter(c -> c.name().equals(name)).findFirst(); }
            @Override public List<LlmConfig> findAll() { return List.copyOf(store.values()); }
            @Override public void deleteById(UUID id) { store.remove(id); }
        };
        config = LlmConfig.lmStudio("test", "llama3", "http://localhost:1234");
        repo.save(config);

        LlmGateway stubGateway = new LlmGateway() {
            @Override
            public void chatStreaming(LlmConfig cfg, LlmRequest req,
                                      Consumer<LlmStreamChunk> handler,
                                      Cancellation cancellation,
                                      ai.mindconnect.llm.port.in.LlmCallListener listener) {
                handler.accept(new LlmStreamChunk.TextDelta("Hello "));
                handler.accept(new LlmStreamChunk.TextDelta("from "));
                handler.accept(new LlmStreamChunk.TextDelta("LM Studio"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 10, 5));
            }
        };

        var registry = new DefaultLlmGatewayRegistry(Map.of(LM_STUDIO, stubGateway));
        service = new RoutingLlmChatService(repo, registry);
    }

    @Test
    void chatStreamingDeliversChunks() {
        LlmRequest request = LlmRequest.streaming(config.name(),
                List.of(LlmMessage.user("Hi")));

        List<String> textParts = new ArrayList<>();
        FinishReason[] finishCaught = new FinishReason[1];
        service.chatStreaming(request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta td -> textParts.add(td.text());
                case LlmStreamChunk.Done d -> finishCaught[0] = d.finishReason();
                case LlmStreamChunk.ToolCallDelta ignored -> {}
                case LlmStreamChunk.ThinkingDelta ignored -> {}
            }
        });

        assertThat(String.join("", textParts)).isEqualTo("Hello from LM Studio");
        assertThat(finishCaught[0]).isEqualTo(FinishReason.STOP);
    }

    @Test
    void throwsWhenConfigNotFound() {
        LlmRequest request = LlmRequest.streaming("unknown-config",
                List.of(LlmMessage.user("Hi")));
        assertThatThrownBy(() -> service.chatStreaming(request, c -> {}))
                .isInstanceOf(ai.mindconnect.common.DomainException.class)
                .hasMessageContaining("LlmConfig not found");
    }
}
