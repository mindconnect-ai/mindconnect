package ai.mindconnect.cli;

import ai.mindconnect.jackson.JsonMapperCustomizer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** The CLI's own beans: its JSON and the HTTP client of the remote mode. The local runtime is the starter's. */
@Configuration
public class CliConfig {

    /**
     * How the CLI writes JSON; the runtime the starter builds in either mode makes the mapper —
     * with the id module its plugins contribute, which the remote mode needs to read agents too.
     */
    @Bean
    JsonMapperCustomizer jsonMapperCustomizer() {
        return mapper -> mapper
                .addModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // FOR REMOTE
    @Bean
    OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
}
