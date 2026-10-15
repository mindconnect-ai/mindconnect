package ai.mindconnect.agentrest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Cross-origin calls to the REST endpoints — a browser app on another
 * origin talking to {@code /api}, {@code /chat/api} or {@code /v1}. Every
 * origin may call by default ({@code mindconnect.cors.allowed-origins: *});
 * an operator narrows it to a list of origins, and then the browser may
 * also send the session cookie along, which it never does for {@code *}.
 *
 * <p>Both server apps scan this package, so both get it. The admin UI app's
 * security chains enable CORS as well, so a preflight passes the filter
 * without a login.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${mindconnect.cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOrigins.isEmpty() ? List.of("*") : allowedOrigins;
        boolean anyOrigin = origins.contains("*");
        registry.addMapping("/**")
                .allowedOriginPatterns(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Credentials only for named origins: the browser refuses
                // "*" with credentials anyway, and a wildcard must not carry
                // the admin session to any site that asks.
                .allowCredentials(!anyOrigin)
                .maxAge(3600);
    }
}
