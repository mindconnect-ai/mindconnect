package ai.mindconnect.common.env;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Remembers every answer of the resolver it wraps — for the span of one unit
 * of work, never longer: a store-backed source read once per name, not once
 * per placeholder. Not thread-safe; one instance per unit of work.
 */
public class MemoizedEnvVarResolver implements EnvVarResolver {

    private final EnvVarResolver delegate;
    private final Map<String, Optional<String>> seen = new HashMap<>();
    private Map<String, String> all;

    public MemoizedEnvVarResolver(EnvVarResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<String> get(String name) {
        return seen.computeIfAbsent(name, delegate::get);
    }

    @Override
    public Map<String, String> asMap() {
        if (all == null) all = delegate.asMap();
        return all;
    }

    @Override
    public boolean personal() {
        return delegate.personal();
    }

    @Override
    public EnvVarResolver shared() {
        EnvVarResolver shared = delegate.shared();
        return shared == delegate ? this : shared.memoized();
    }

    @Override
    public EnvVarResolver memoized() {
        return this;
    }
}
