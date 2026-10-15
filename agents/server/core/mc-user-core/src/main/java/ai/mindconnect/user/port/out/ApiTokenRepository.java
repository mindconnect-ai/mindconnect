package ai.mindconnect.user.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Issued API tokens, by id and by the hash of their secret. An adapter is
 * bound to one namespace when it is built; nothing here names it.
 */
public interface ApiTokenRepository {

    /** Inserts or replaces the token with this id. */
    void save(ApiToken token);

    Optional<ApiToken> findById(ApiTokenId id);

    /** The token whose secret hashes to {@code tokenHash}; empty when none does. */
    Optional<ApiToken> findByHash(String tokenHash);

    /** A user's tokens, in no particular order. */
    List<ApiToken> findByUser(UserId userId);

    /** Removes the token; a missing id is not an error. */
    void deleteById(ApiTokenId id);

    /**
     * Records that the token authenticated at {@code at}. Unlike
     * {@link #save}, this never creates a token: one revoked while a request
     * was using it stays revoked.
     */
    void recordUse(ApiTokenId id, Instant at);
}
