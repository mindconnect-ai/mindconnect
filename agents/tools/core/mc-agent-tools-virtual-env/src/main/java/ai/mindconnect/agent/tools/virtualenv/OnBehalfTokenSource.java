package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.util.Optional;

/**
 * The token for a tool call: signed for the user the call runs for, in the
 * namespace the runtime is working in right now. A call without a user — the
 * installation's own runs — is signed for {@value #INSTALLATION}.
 */
public class OnBehalfTokenSource implements TokenSource {

    /** Subject of calls no user made. */
    public static final String INSTALLATION = "installation";

    private final OnBehalfTokens tokens;
    private final ScopeSupplier scopes;

    /** @param scopes where the current namespace comes from; null means {@link Namespace#DEFAULT} */
    public OnBehalfTokenSource(OnBehalfTokens tokens, ScopeSupplier scopes) {
        this.tokens = tokens;
        this.scopes = scopes;
    }

    @Override
    public Optional<String> token(ToolCallScope scope) {
        String subject = scope == null || scope.userId() == null ? INSTALLATION : scope.userId().value();
        Namespace namespace = scopes == null ? Namespace.DEFAULT : scopes.namespace();
        return Optional.of(tokens.token(subject, namespace.value()));
    }
}
