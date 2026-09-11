package ai.mindconnect.agentapp;

import ai.mindconnect.agent.security.ApiAuthentication;
import ai.mindconnect.agent.security.DevUserFilter;
import ai.mindconnect.agent.security.UserRecorder;
import ai.mindconnect.agent.security.UserRecordingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The agent server serves the REST API to programs, so its security has no
 * browser in it: no login page, no session, no CSRF token.
 *
 * <ul>
 *   <li><b>auth enabled</b> ({@code mindconnect.auth.enabled=true}): every
 *       request carries a bearer token — a JWT of the configured issuer
 *       ({@code mindconnect.auth.jwt.issuer-uri}) or a personal API token
 *       created in the admin UI of an installation sharing this server's data.
 *       Without one: 401 with {@code WWW-Authenticate: Bearer} and a JSON body
 *       saying why. The OpenAPI
 *       description and Swagger UI stay readable.</li>
 *   <li><b>auth disabled</b> (the default): every request runs as the dev user
 *       ({@code mindconnect.auth.dev-user}).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "true")
    SecurityFilterChain securedFilterChain(HttpSecurity http, ApiAuthentication api) throws Exception {
        api.apply(http
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                .anyRequest().authenticated()));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(HttpSecurity http,
                                        @Value("${mindconnect.auth.dev-user:mc_user}") String devUser,
                                        UserRecorder userRecorder) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(new DevUserFilter(devUser), AuthorizationFilter.class)
            .addFilterBefore(new UserRecordingFilter(userRecorder), AuthorizationFilter.class);
        return http.build();
    }
}
