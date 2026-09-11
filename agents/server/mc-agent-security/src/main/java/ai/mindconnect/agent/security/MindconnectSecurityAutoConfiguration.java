package ai.mindconnect.agent.security;

import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.UserService;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;

/**
 * The parts the apps compose their security from: the user and token
 * services over the persistence starter's repositories, the caller resolver
 * for the REST layer, and {@link ApiAuthentication}. Filter chains are not
 * defined here — which paths take a browser login, a bearer token or nothing
 * is each app's decision.
 *
 * <pre>
 * mindconnect:
 *   auth:
 *     jwt:
 *       issuer-uri: https://auth.example.com/realms/mindconnect   # KC_ISSUER_URI
 *       audiences: mc-api                                          # optional
 * </pre>
 */
@AutoConfiguration(afterName = {
        "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
        "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"})
@ConditionalOnClass(HttpSecurity.class)
@ConditionalOnBean({UserRepository.class, ApiTokenRepository.class})
public class MindconnectSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    UserService userService(UserRepository users) {
        return new UserService(users);
    }

    @Bean
    @ConditionalOnMissingBean
    ApiTokenService apiTokenService(ApiTokenRepository tokens) {
        return new ApiTokenService(tokens);
    }

    @Bean
    @ConditionalOnMissingBean
    UserRecorder userRecorder(UserService users) {
        return new UserRecorder(users);
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserResolver.class)
    CurrentUserResolver currentUserResolver() {
        return new SecurityCurrentUserResolver();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder mindconnectJwtDecoder(@Value("${mindconnect.auth.jwt.issuer-uri:}") String issuerUri,
                                     @Value("${mindconnect.auth.jwt.audiences:}") List<String> audiences) {
        return ApiAuthentication.jwtDecoder(issuerUri, audiences);
    }

    @Bean
    @ConditionalOnMissingBean
    ApiAuthentication apiAuthentication(ApiTokenService tokens, UserRecorder recorder, JwtDecoder jwtDecoder) {
        return new ApiAuthentication(tokens, recorder, jwtDecoder);
    }

    /**
     * With authentication on, the OpenAPI description declares the bearer
     * scheme, so Swagger UI offers <b>Authorize</b> and "Try it out" sends the
     * token — the API does not take the browser session Swagger UI runs in.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springdoc.core.customizers.OpenApiCustomizer")
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "true")
    static class BearerOpenApi {

        static final String SCHEME = "bearer";

        @Bean
        OpenApiCustomizer mindconnectBearerOpenApi() {
            return openApi -> openApi
                    .schemaRequirement(SCHEME, new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .description("A personal API token (mct_…, created on the profile page) "
                                    + "or an access token of the identity provider"))
                    .addSecurityItem(new SecurityRequirement().addList(SCHEME));
        }
    }
}
