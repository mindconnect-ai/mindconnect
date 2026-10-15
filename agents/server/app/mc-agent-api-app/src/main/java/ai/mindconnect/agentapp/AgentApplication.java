package ai.mindconnect.agentapp;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.concurrent.TimeUnit;

/**
 * The agent server. The agent runtime — repositories, LLM layer, tools, turn
 * loop — comes from {@code mc-agent-starter-runtime}, built from the same
 * features the embedded builder installs and configured from the
 * {@code mindconnect.*} properties; what is left here is the server's own.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
    "ai.mindconnect.agentapp",
    "ai.mindconnect.agentrest"
})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    /** The server's own HTTP client, for the SSE streams it consumes — no read timeout. */
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
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** The key the runtime encrypts stored LLM credentials with — required on a server. */
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
