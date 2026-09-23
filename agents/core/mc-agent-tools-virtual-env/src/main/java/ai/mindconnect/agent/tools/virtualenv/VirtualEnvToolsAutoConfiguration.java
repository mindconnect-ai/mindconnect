package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * With {@code mindconnect.virtual-env.client.url} set, the agent's file tools,
 * {@code bash} and {@code code_execute} work on the session's workspace on that
 * server instead of this machine.
 *
 * <ul>
 *   <li>{@code mindconnect.virtual-env.client.url} — e.g. {@code http://10.0.0.5:9120}</li>
 *   <li>{@code .default-template} — the template of a binding without an
 *       {@code environment} override (default {@code python})</li>
 *   <li>{@code .issuer} — sign a short-lived token per call for the calling user
 *       ({@link OnBehalfTokens}) under this issuer name, and publish its key at
 *       {@value OnBehalfJwksController#PATH}. The server trusts that issuer and
 *       tells users apart by it.</li>
 *   <li>{@code .audience} — the {@code aud} of those tokens (default {@code mc-virtual-env})</li>
 *   <li>{@code .token-lifetime} — how long one is valid (default 5m)</li>
 *   <li>{@code .token} — instead: one fixed bearer token for every call; empty for a server
 *       without authentication</li>
 *   <li>{@code .queue-timeout} — how long a command waits for a queued environment (default 10m)</li>
 *   <li>{@code .request-timeout} — for file calls (default 60s)</li>
 *   <li>{@code .idle-timeout} — how long the client keeps what it knows about a workspace
 *       nobody uses (default 2h); the server stops idle environments by itself. Keep it
 *       below the server's retention</li>
 * </ul>
 */
@AutoConfiguration
// Not @ConditionalOnProperty: an empty value from a placeholder like ${MC_VIRTUAL_ENV_URL:} counts as set there.
@ConditionalOnExpression("!'${mindconnect.virtual-env.client.url:}'.isBlank()")
public class VirtualEnvToolsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VirtualEnvToolsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("!'${mindconnect.virtual-env.client.issuer:}'.isBlank()")
    public OnBehalfTokens virtualEnvOnBehalfTokens(Environment env) {
        return new OnBehalfTokens(env.getRequiredProperty("mindconnect.virtual-env.client.issuer"),
                env.getProperty("mindconnect.virtual-env.client.audience", "mc-virtual-env"),
                env.getProperty("mindconnect.virtual-env.client.token-lifetime", Duration.class, Duration.ofMinutes(5)));
    }

    @Bean
    @ConditionalOnMissingBean(WorkspaceProvider.class)
    public VirtualEnvWorkspaceProvider virtualEnvWorkspaceProvider(Environment env,
                                                                   ObjectProvider<OnBehalfTokens> onBehalf,
                                                                   ObjectProvider<ScopeSupplier> scopes) {
        String url = env.getRequiredProperty("mindconnect.virtual-env.client.url");
        String token = env.getProperty("mindconnect.virtual-env.client.token", "");
        String template = env.getProperty("mindconnect.virtual-env.client.default-template", "python");
        Duration queueTimeout = env.getProperty("mindconnect.virtual-env.client.queue-timeout", Duration.class,
                Duration.ofMinutes(10));
        Duration requestTimeout = env.getProperty("mindconnect.virtual-env.client.request-timeout", Duration.class,
                Duration.ofSeconds(60));
        Duration idleTimeout = env.getProperty("mindconnect.virtual-env.client.idle-timeout", Duration.class,
                VirtualEnvWorkspaceProvider.DEFAULT_IDLE_TIMEOUT);
        OnBehalfTokens signer = onBehalf.getIfAvailable();
        TokenSource tokens;
        String auth;
        if (signer != null) {
            if (!token.isBlank()) {
                log.warn("mindconnect.virtual-env.client.token is ignored: calls carry on-behalf tokens of issuer {}",
                        signer.issuer());
            }
            tokens = new OnBehalfTokenSource(signer, scopes.getIfAvailable());
            auth = "on-behalf tokens of issuer " + signer.issuer();
        } else if (!token.isBlank()) {
            tokens = TokenSource.fixed(token);
            auth = "a fixed token";
        } else {
            tokens = TokenSource.none();
            auth = "no token";
        }
        VirtualEnvClient client = new VirtualEnvClient(URI.create(url), tokens, requestTimeout);
        log.info("Agent tools work in virtual environments on {} (default template {}, {})", url, template, auth);
        return new VirtualEnvWorkspaceProvider(client, template, queueTimeout, idleTimeout, Clock.systemUTC());
    }

    /** The public key of the on-behalf tokens, where the server fetches it. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    // Not @ConditionalOnBean: a member class is processed before the outer class's own bean methods.
    @ConditionalOnExpression("!'${mindconnect.virtual-env.client.issuer:}'.isBlank()")
    static class Jwks {

        @Bean
        OnBehalfJwksController onBehalfJwksController(OnBehalfTokens tokens) {
            return new OnBehalfJwksController(tokens);
        }
    }
}
