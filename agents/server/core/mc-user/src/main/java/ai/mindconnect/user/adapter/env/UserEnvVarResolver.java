package ai.mindconnect.user.adapter.env;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The variables of the user behind the current unit of work — read from
 * their {@link User} record on every lookup, so an edit takes effect on the
 * next call; wrap in {@link #memoized()} for one unit of work. Who that is
 * comes from the {@link ScopeSupplier}: a request bound to a user, a queued
 * task stamped with one. Work on nobody's behalf — a start-up routine, a
 * library call — has no user and gets nothing here, so the next source of
 * the chain answers.
 *
 * <p>A {@linkplain #personal() personal} source: what it holds reaches a
 * config's API key, not its endpoint or model. The repository hands the
 * values out decrypted (see {@link EncryptingUserRepository}).
 */
public class UserEnvVarResolver implements EnvVarResolver {

    private final UserRepository users;
    private final ScopeSupplier scope;

    public UserEnvVarResolver(UserRepository users, ScopeSupplier scope) {
        this.users = Objects.requireNonNull(users, "users");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Optional<String> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(asMap().get(name));
    }

    @Override
    public Map<String, String> asMap() {
        return scope.get().userIfAny().flatMap(users::findById).map(User::environment).orElse(Map.of());
    }

    @Override
    public boolean personal() {
        return true;
    }

    @Override
    public String toString() {
        return "UserEnvVarResolver";
    }
}
