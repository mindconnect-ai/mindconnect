package ai.mindconnect.llm.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.mindconnect.llm.management.api.domain.LlmCapability;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmConfigType;
import ai.mindconnect.llm.management.api.domain.LlmPrice;
import ai.mindconnect.llm.management.api.domain.LlmPriceId;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.domain.RateLimitConfig;
import ai.mindconnect.llm.management.api.domain.RetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The JSON below is what the annotated records wrote before the annotations
 * moved into this module, captured from them; stores hold exactly this.
 */
class LlmJsonModuleTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new LlmJsonModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void a_config_with_every_field_is_written_as_before() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reasoning_effort", "low");
        params.put("top_p", 0.9);
        LlmConfig config = new LlmConfig(LlmConfigId.of("cfg-1"), "agent-default", LlmProvider.OPENAI,
                "gpt-5.4-mini", "https://api.openai.com/v1", "enc:abc", 0.3, 4096, params, 128000, false, null,
                new RetryConfig(true, 3, 1000, 20000), new RateLimitConfig(2), LlmConfigType.CHAT,
                new LinkedHashSet<>(List.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION)),
                List.of("fallback-a"), 7L);

        String json = mapper.writeValueAsString(config);

        assertThat(json).isEqualTo("{\"id\":\"cfg-1\",\"name\":\"agent-default\",\"provider\":\"OPENAI\",\"model\":\"gpt-5.4-mini\",\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"enc:abc\",\"defaultTemperature\":0.3,\"maxOutputTokens\":4096,\"additionalParams\":{\"reasoning_effort\":\"low\",\"top_p\":0.9},\"contextWindowTokens\":128000,\"isAlias\":false,\"delegatesTo\":null,\"retry\":{\"enabled\":true,\"maxAttempts\":3,\"baseBackoffMillis\":1000,\"maxBackoffMillis\":20000},\"rateLimit\":{\"maxConcurrentRequests\":2},\"type\":\"CHAT\",\"capabilities\":[\"TOOL_CALLING\",\"VISION\"],\"fallbackModels\":[\"fallback-a\"],\"version\":7}");
        assertThat(mapper.readValue(json, LlmConfig.class)).isEqualTo(config);
    }

    @Test
    void an_alias_and_a_minimal_config_are_written_as_before() throws Exception {
        assertThat(withoutId(mapper.writeValueAsString(LlmConfig.alias("default", "agent-default"))))
                .isEqualTo("{\"id\":\"ID\",\"name\":\"default\",\"provider\":null,\"model\":null,\"baseUrl\":null,\"apiKey\":null,\"defaultTemperature\":0.0,\"maxOutputTokens\":0,\"additionalParams\":{},\"contextWindowTokens\":null,\"isAlias\":true,\"delegatesTo\":\"agent-default\",\"retry\":null,\"rateLimit\":null,\"type\":\"CHAT\",\"capabilities\":null,\"fallbackModels\":[],\"version\":null}");
        assertThat(withoutId(mapper.writeValueAsString(LlmConfig.lmStudio("local", "qwen", "http://localhost:1234/v1"))))
                .isEqualTo("{\"id\":\"ID\",\"name\":\"local\",\"provider\":\"LM_STUDIO\",\"model\":\"qwen\",\"baseUrl\":\"http://localhost:1234/v1\",\"apiKey\":\"lm-studio\",\"defaultTemperature\":0.7,\"maxOutputTokens\":2048,\"additionalParams\":{},\"contextWindowTokens\":131072,\"isAlias\":false,\"delegatesTo\":null,\"retry\":null,\"rateLimit\":null,\"type\":\"CHAT\",\"capabilities\":null,\"fallbackModels\":[],\"version\":null}");
    }

    @Test
    void a_stored_config_reads_with_retry_defaults_and_ignores_unknown_fields() throws Exception {
        String stored = "{\"id\":\"cfg-2\",\"name\":\"x\",\"provider\":\"OPENAI\",\"model\":\"m\",\"unknownField\":1,"
                + "\"retry\":{\"maxAttempts\":2},\"rateLimit\":{\"maxConcurrentRequests\":0}}";

        assertThat(mapper.readValue(stored, LlmConfig.class).toString()).isEqualTo("LlmConfig[id=cfg-2, name=x, provider=OPENAI, model=m, baseUrl=null, apiKey=null, defaultTemperature=0.0, maxOutputTokens=0, additionalParams={}, contextWindowTokens=null, isAlias=false, delegatesTo=null, retry=RetryConfig[enabled=true, maxAttempts=2, baseBackoffMillis=2000, maxBackoffMillis=30000], rateLimit=RateLimitConfig[maxConcurrentRequests=1], type=CHAT, capabilities=null, fallbackModels=[], version=null]");
    }

    @Test
    void prices_are_written_and_read_as_before() throws Exception {
        LlmPrice price = new LlmPrice(LlmPriceId.of("p-1"), "agent-default", "gpt-5.4-mini",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "USD",
                new BigDecimal("0.15"), new BigDecimal("0.60"), new BigDecimal("0.075"));
        LlmPrice open = new LlmPrice(LlmPriceId.of("p-2"), "agent-default", null,
                LocalDate.of(2026, 1, 1), null, "EUR", new BigDecimal("1"), new BigDecimal("2"), null);

        assertThat(mapper.writeValueAsString(price)).isEqualTo("{\"id\":\"p-1\",\"configName\":\"agent-default\",\"model\":\"gpt-5.4-mini\",\"validFrom\":\"2026-01-01\",\"validTo\":\"2026-07-01\",\"currency\":\"USD\",\"inputPerMillion\":0.15,\"outputPerMillion\":0.60,\"cachedInputPerMillion\":0.075}");
        assertThat(mapper.writeValueAsString(open)).isEqualTo("{\"id\":\"p-2\",\"configName\":\"agent-default\",\"model\":null,\"validFrom\":\"2026-01-01\",\"validTo\":null,\"currency\":\"EUR\",\"inputPerMillion\":1,\"outputPerMillion\":2,\"cachedInputPerMillion\":null}");
        String stored = "{\"id\":\"p-3\",\"configName\":\"c\",\"model\":\"m\",\"validFrom\":\"2026-02-01\",\"extra\":true,"
                + "\"inputPerMillion\":1,\"outputPerMillion\":2}";
        assertThat(mapper.readValue(stored, LlmPrice.class).toString()).isEqualTo("LlmPrice[id=p-3, configName=c, model=m, validFrom=2026-02-01, validTo=null, currency=USD, inputPerMillion=1, outputPerMillion=2, cachedInputPerMillion=null]");
    }

    @Test
    void an_invalid_id_is_refused_on_reading() {
        assertThatThrownBy(() -> mapper.readValue("\"Not Valid\"", LlmConfigId.class))
                .hasMessageContaining("is not a valid id");
    }

    private static String withoutId(String json) {
        return json.replaceFirst("\"id\":\"[^\"]+\"", "\"id\":\"ID\"");
    }
}
