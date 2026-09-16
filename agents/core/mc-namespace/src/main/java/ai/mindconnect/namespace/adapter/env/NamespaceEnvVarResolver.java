package ai.mindconnect.namespace.adapter.env;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The variables of the namespace the current unit of work runs in — read
 * from its {@link NamespaceDefinition} on every lookup, so an edit takes
 * effect on the next call; wrap in {@link #memoized()} for one unit of work.
 * Which namespace that is comes from the {@link ScopeSupplier}; a namespace
 * without a record, or without variables (the default namespace never has
 * any), lets the next source of the chain answer. The repository hands the
 * values out decrypted (see {@link EncryptingNamespaceRepository}).
 */
public class NamespaceEnvVarResolver implements EnvVarResolver {

    private final NamespaceRepository namespaces;
    private final ScopeSupplier scope;

    public NamespaceEnvVarResolver(NamespaceRepository namespaces, ScopeSupplier scope) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Optional<String> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(asMap().get(name));
    }

    @Override
    public Map<String, String> asMap() {
        return namespaces.findById(scope.namespace()).map(NamespaceDefinition::environment).orElse(Map.of());
    }

    @Override
    public String toString() {
        return "NamespaceEnvVarResolver";
    }
}
