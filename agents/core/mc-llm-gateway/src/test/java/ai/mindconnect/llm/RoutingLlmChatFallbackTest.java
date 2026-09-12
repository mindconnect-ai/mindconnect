package ai.mindconnect.llm;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.LlmTransientException;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;
import ai.mindconnect.llm.service.RoutingLlmChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rate-limit fallback chain: a config that names fallback models is
 * re-routed at the next one when its provider answers 429/529.
 */
class RoutingLlmChatFallbackTest {

    private final Map<LlmConfigId, LlmConfig> store = new ConcurrentHashMap<>();
    private final Map<String, LlmGateway> gateways = new HashMap<>();
    /** Config names the registry was asked for, in order. */
    private final List<String> routedTo = new ArrayList<>();
    private RoutingLlmChatService service;

    private LlmConfigRepository repository() {
        return new LlmConfigRepository() {
            @Override public void save(LlmConfig c) { store.put(c.id(), c); }
            @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.ofNullable(store.get(id)); }
            @Override public Optional<LlmConfig> findByName(String name) {
                return store.values().stream().filter(c -> c.name().equals(name)).findFirst();
            }
            @Override public List<LlmConfig> findAll() { return List.copyOf(store.values()); }
            @Override public void deleteById(LlmConfigId id) { store.remove(id); }
        };
    }

    @BeforeEach
    void setUp() {
        LlmConfigRepository repository = repository();
        LlmGatewayRegistry registry = config -> {
            routedTo.add(config.name());
            LlmGateway gateway = gateways.get(config.name());
            if (gateway == null) throw new IllegalStateException("no stub gateway for " + config.name());
            return gateway;
        };
        service = new RoutingLlmChatService(repository, registry);
    }

    /** A config that names its fallbacks, plus a stub gateway behind it. */
    private LlmConfig config(String name, LlmGateway gateway, String... fallbacks) {
        LlmConfig config = LlmConfig.lmStudio(name, name + "-model", "http://localhost:1234")
                .withFallbackModels(List.of(fallbacks));
        store.put(config.id(), config);
        gateways.put(name, gateway);
        return config;
    }

    /** Answers with two chunks and a Done. */
    private static LlmGateway answering(String text) {
        return (config, request, handler, cancellation, listener) -> {
            handler.accept(new LlmStreamChunk.TextDelta(text));
            handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
        };
    }

    /** Rejects with a rate limit before streaming anything, as a real gateway does. */
    private static LlmGateway rateLimited() {
        return (config, request, handler, cancellation, listener) -> {
            throw new LlmTransientException(429, 0, "rate limited: " + config.name());
        };
    }

    private String chat(String configName) {
        StringBuilder text = new StringBuilder();
        service.chatStreaming(LlmRequest.streaming(configName, List.of(LlmMessage.user("Hi"))),
                chunk -> {
                    if (chunk instanceof LlmStreamChunk.TextDelta td) text.append(td.text());
                },
                Cancellation.none(), LlmCallListener.NOOP);
        return text.toString();
    }

    @Test
    void aRateLimitedConfigFallsBackToTheNextNamedModel() {
        config("primary", rateLimited(), "backup");
        config("backup", answering("from backup"));

        assertThat(chat("primary")).isEqualTo("from backup");
        assertThat(routedTo).containsExactly("primary", "backup");
    }

    @Test
    void theFallbackCallIsRoutedUnderTheFallbackConfigName() {
        List<String> seen = new ArrayList<>();
        config("primary", rateLimited(), "backup");
        config("backup", (cfg, request, handler, cancellation, listener) -> {
            seen.add(request.configName());
            handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
        });

        chat("primary");

        assertThat(seen).containsExactly("backup");
    }

    @Test
    void fallbacksAreTriedInOrderUntilOneAnswers() {
        config("primary", rateLimited(), "second", "third");
        config("second", rateLimited());
        config("third", answering("third answered"));

        assertThat(chat("primary")).isEqualTo("third answered");
        assertThat(routedTo).containsExactly("primary", "second", "third");
    }

    @Test
    void theRateLimitPropagatesWhenEveryFallbackIsLimitedToo() {
        config("primary", rateLimited(), "backup");
        config("backup", rateLimited());

        assertThatThrownBy(() -> chat("primary"))
                .isInstanceOf(LlmTransientException.class)
                .hasMessageContaining("backup");
        assertThat(routedTo).containsExactly("primary", "backup");
    }

    @Test
    void nothingFallsBackOnceTheAnswerHasStartedStreaming() {
        // A 429 in the middle of a stream cannot be retried on another model:
        // the caller already saw half an answer.
        config("primary", (cfg, request, handler, cancellation, listener) -> {
            handler.accept(new LlmStreamChunk.TextDelta("half an answer"));
            throw new LlmTransientException(429, 0, "limited mid-stream");
        }, "backup");
        config("backup", answering("backup"));

        assertThatThrownBy(() -> chat("primary")).isInstanceOf(LlmTransientException.class);
        assertThat(routedTo).containsExactly("primary");
    }

    @Test
    void aNonTransientFailureDoesNotFallBack() {
        config("primary", (cfg, request, handler, cancellation, listener) -> {
            throw new IllegalStateException("bad request");
        }, "backup");
        config("backup", answering("backup"));

        assertThatThrownBy(() -> chat("primary")).isInstanceOf(IllegalStateException.class);
        assertThat(routedTo).containsExactly("primary");
    }

    @Test
    void aFallbackNamingNoKnownConfigIsSkipped() {
        config("primary", rateLimited(), "typo", "backup");
        config("backup", answering("backup answered"));

        assertThat(chat("primary")).isEqualTo("backup answered");
        assertThat(routedTo).containsExactly("primary", "backup");
    }

    @Test
    void aFallbackMayNameAnAliasAndIsResolvedThroughIt() {
        config("primary", rateLimited(), "cloud");
        config("real-cloud", answering("cloud answered"));
        LlmConfig alias = LlmConfig.alias("cloud", "real-cloud");
        store.put(alias.id(), alias);

        assertThat(chat("primary")).isEqualTo("cloud answered");
        assertThat(routedTo).containsExactly("primary", "real-cloud");
    }

    @Test
    void aFallbackListThatPointsBackAtThePrimaryDoesNotCallItTwice() {
        config("primary", rateLimited(), "primary", "backup");
        config("backup", answering("backup answered"));

        assertThat(chat("primary")).isEqualTo("backup answered");
        assertThat(routedTo).containsExactly("primary", "backup");
    }
}
