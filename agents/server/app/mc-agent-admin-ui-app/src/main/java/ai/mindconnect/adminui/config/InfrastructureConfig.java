package ai.mindconnect.adminui.config;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class InfrastructureConfig {

    /**
     * Shared client for SSE-streamed LLM calls — read timeout is zero so a
     * long-running chat completion isn't aborted mid-token. The web tools
     * build their own client internally (with bounded timeouts) so they
     * never use this one.
     */
    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // no timeout for SSE streams
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * The secret key encrypts stored LLM credentials. It is required and has
     * no default on purpose: a hard-coded fallback would mean credentials are
     * "encrypted" with a publicly known key. Provide a strong, private value
     * via {@code mindconnect.encryption.secret-key} (env var
     * {@code MINDCONNECT_ENCRYPTION_SECRET_KEY}).
     */
    @Bean
    EncryptionHelper encryptionHelper(
            @Value("${mindconnect.encryption.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                "mindconnect.encryption.secret-key is not set. It encrypts stored LLM "
                + "credentials, so there is no default. Set it via the "
                + "MINDCONNECT_ENCRYPTION_SECRET_KEY environment variable (use a strong, "
                + "private 32-character value).");
        }
        return new EncryptionHelper(secretKey);
    }
}
