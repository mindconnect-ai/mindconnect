package ai.mindconnect.llm.jackson;

import ai.mindconnect.llm.management.api.domain.LlmCapability;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmPrice;
import ai.mindconnect.llm.management.api.domain.LlmPriceId;
import ai.mindconnect.llm.management.api.domain.RateLimitConfig;
import ai.mindconnect.llm.management.api.domain.RetryConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.util.Set;

/**
 * The stored form of LLM configs and prices, as files, database documents and
 * REST bodies hold it. The records know no JSON library; this module says
 * what their annotations used to: ids as plain values, configs and prices
 * lenient on unknown fields, derived accessors left out, retry and rate-limit
 * settings read with their defaults. Dates need the host's
 * {@code JavaTimeModule}, as they always did.
 */
public class LlmJsonModule extends SimpleModule {

    public LlmJsonModule() {
        super("mindconnect-llm");
        setMixInAnnotation(LlmConfigId.class, IdMixin.class);
        setMixInAnnotation(LlmPriceId.class, IdMixin.class);
        setMixInAnnotation(LlmConfig.class, ConfigMixin.class);
        setMixInAnnotation(LlmPrice.class, PriceMixin.class);
        setMixInAnnotation(RetryConfig.class, RetryMixin.class);
        setMixInAnnotation(RateLimitConfig.class, RateLimitMixin.class);
    }

    abstract static class IdMixin {
        @JsonValue
        abstract String value();

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static Object of(String value) {
            throw new UnsupportedOperationException("mix-in");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    abstract static class ConfigMixin {
        @JsonIgnore
        abstract boolean declaresCapabilities();

        @JsonIgnore
        abstract Set<LlmCapability> effectiveCapabilities();

        @JsonIgnore
        abstract boolean isEmbedding();

        @JsonIgnore
        abstract boolean isSpeechToText();

        @JsonIgnore
        abstract boolean hasFallbackModels();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    abstract static class PriceMixin {
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        abstract LocalDate validFrom();

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        abstract LocalDate validTo();
    }

    abstract static class RetryMixin {
        @JsonCreator
        static RetryConfig fromJson(@JsonProperty("enabled") Boolean enabled,
                                    @JsonProperty("maxAttempts") Integer maxAttempts,
                                    @JsonProperty("baseBackoffMillis") Long baseBackoffMillis,
                                    @JsonProperty("maxBackoffMillis") Long maxBackoffMillis) {
            throw new UnsupportedOperationException("mix-in");
        }
    }

    abstract static class RateLimitMixin {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        static RateLimitConfig of(@JsonProperty("maxConcurrentRequests") int maxConcurrentRequests) {
            throw new UnsupportedOperationException("mix-in");
        }
    }
}
