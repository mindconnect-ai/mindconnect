package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed ports and a settable clock for the service tests — the real adapters live in mc-user. */
class MapRepositories {

    private MapRepositories() {}

    static class Users implements UserRepository {
        final Map<UserId, User> byId = new ConcurrentHashMap<>();
        int saves;

        @Override public Optional<User> findById(UserId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<User> findAll() { return List.copyOf(byId.values()); }
        @Override public void save(User user) { saves++; byId.put(user.id(), user); }
    }

    static class Tokens implements ApiTokenRepository {
        final Map<ApiTokenId, ApiToken> byId = new ConcurrentHashMap<>();
        int writes;

        @Override public void save(ApiToken token) { writes++; byId.put(token.id(), token); }
        @Override public Optional<ApiToken> findById(ApiTokenId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<ApiToken> findByHash(String tokenHash) {
            return byId.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
        }
        @Override public List<ApiToken> findByUser(UserId userId) {
            return byId.values().stream().filter(t -> t.userId().equals(userId)).toList();
        }
        @Override public void deleteById(ApiTokenId id) { byId.remove(id); }
        @Override public void recordUse(ApiTokenId id, Instant at) {
            writes++;
            byId.computeIfPresent(id, (k, t) -> t.usedAt(at));
        }
    }

    static class SettableClock extends Clock {
        Instant now;

        SettableClock(Instant now) { this.now = now; }

        void advance(java.time.Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
