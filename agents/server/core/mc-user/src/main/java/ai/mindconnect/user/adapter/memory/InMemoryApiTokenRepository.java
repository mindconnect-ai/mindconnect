package ai.mindconnect.user.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.port.out.ApiTokenRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ApiTokenRepository} on the heap — for tests and embedders that keep nothing. */
public class InMemoryApiTokenRepository implements ApiTokenRepository {

    private final Map<ApiTokenId, ApiToken> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(ApiToken token) {
        tokens.put(token.id(), token);
    }

    @Override
    public Optional<ApiToken> findById(ApiTokenId id) {
        return Optional.ofNullable(tokens.get(id));
    }

    @Override
    public Optional<ApiToken> findByHash(String tokenHash) {
        return tokens.values().stream().filter(token -> token.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public List<ApiToken> findByUser(UserId userId) {
        return tokens.values().stream().filter(token -> token.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(ApiTokenId id) {
        tokens.remove(id);
    }

    @Override
    public void recordUse(ApiTokenId id, Instant at) {
        tokens.computeIfPresent(id, (key, token) -> token.usedAt(at));
    }
}
