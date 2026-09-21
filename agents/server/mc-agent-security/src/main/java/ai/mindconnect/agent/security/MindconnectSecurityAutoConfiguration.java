package ai.mindconnect.agent.security;

import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.UserToolRoster;
import ai.mindconnect.user.adapter.tool.StoredUserToolRoster;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.service.UserToolService;
import ai.mindconnect.credentials.adapter.tool.RefreshingConnections;
import ai.mindconnect.credentials.adapter.tool.ServiceConnections;
import ai.mindconnect.credentials.oauth.OAuthConnections;
import ai.mindconnect.credentials.oauth.OAuthFlow;
import ai.mindconnect.credentials.oauth.OAuthProviderContributions;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import ai.mindconnect.credentials.port.out.ConnectionRepository;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.NotificationService;
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

    /**
     * Present only where there is somewhere to keep notices — a host that
     * assembled no {@link NotificationRepository} has no bell and nothing
     * raises into the void.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NotificationRepository.class)
    NotificationService notificationService(NotificationRepository notifications) {
        return new NotificationService(notifications);
    }

    /**
     * What each user's screens remember — the folder last open, the view last
     * chosen. Present whenever the host assembled a {@link
     * ai.mindconnect.user.port.out.PreferenceRepository}; a screen that finds
     * no service simply starts where it always did.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ai.mindconnect.user.port.out.PreferenceRepository.class)
    ai.mindconnect.user.service.PreferenceService preferenceService(
            ai.mindconnect.user.port.out.PreferenceRepository preferences) {
        return new ai.mindconnect.user.service.PreferenceService(preferences);
    }

    /**
     * What each user keeps in their own tool account, and the roster the
     * runtime lays over an agent's list for them. Found by the runtime through
     * its bean fallback into this context, like {@link Connections}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(UserToolRepository.class)
    UserToolService userToolService(UserToolRepository userTools) {
        return new UserToolService(userTools);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(UserToolService.class)
    UserToolRoster userToolRoster(UserToolService userTools, ObjectProvider<ToolRegistry> registry) {
        return new StoredUserToolRoster(userTools, registry::getIfAvailable);
    }

    /**
     * The accounts users attached, beside the other services that are keyed
     * by person. {@link Connections} is the port the tool registry looks
     * connections up through; the runtime finds this bean through its
     * bean fallback into the host context, so no feature has to register it.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConnectionRepository.class)
    ConnectionService connectionService(ConnectionRepository connections) {
        return new ConnectionService(connections);
    }

    /**
     * Deliberately not called {@code toolConnections}: the Admin UI has a
     * {@code ToolConnections} service of its own, and a bean method of that
     * name would collide with the scanned class rather than with anything
     * meaningful.
     */
    /**
     * Stores the app registrations the modules on the classpath brought along,
     * once. A name the installation already has is left alone — an operator's
     * own registration is not overwritten by a restart.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OAuthProviderRepository.class)
    OAuthProviderContributions oAuthProviderContributions(OAuthProviderRepository providers) {
        OAuthProviderContributions contributions = new OAuthProviderContributions(providers);
        contributions.install();
        return contributions;
    }

    @Bean
    @ConditionalOnMissingBean
    OAuthFlow oAuthFlow(ObjectMapper objectMapper) {
        return new OAuthFlow(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConnectionService.class, OAuthProviderRepository.class})
    OAuthConnections oAuthConnections(ConnectionService connections, OAuthProviderRepository providers,
                                      OAuthFlow flow) {
        return new OAuthConnections(connections, providers, flow);
    }

    /**
     * Where a token is renewed: on the way to a tool, which is the one place
     * every call passes and exactly when it is worth renewing. Without an
     * {@link OAuthConnections} — a host that registered no OAuth app — the
     * plain lookup is used and nothing is refreshed, because nothing can expire.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConnectionService.class)
    Connections connectionLookup(ConnectionService connections, ObjectProvider<OAuthConnections> oauth) {
        Connections lookup = new ServiceConnections(connections);
        OAuthConnections refreshing = oauth.getIfAvailable();
        return refreshing == null ? lookup : new RefreshingConnections(lookup, refreshing);
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
