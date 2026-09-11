package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.port.out.ApiTokenRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ApiTokenRepository} on the file system: one JSON document per token
 * under {@code <storageDir>/<namespace>/system/api-tokens/<id>.json}. A lookup
 * by hash reads the directory — an installation has a handful of tokens per
 * user, not millions, and the Postgres adapter indexes the hash.
 *
 * <p>Writes go through one lock, so that recording a use and revoking the
 * same token cannot interleave into a revoked token written back.
 */
public class FileApiTokenRepository implements ApiTokenRepository {

    private final FileDocuments<ApiToken> tokens;
    private final Object lock = new Object();

    public FileApiTokenRepository(Path storageDir, Namespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        this.tokens = new FileDocuments<>(
                storageDir.resolve(namespace.value()).resolve("system").resolve("api-tokens"), ApiToken.class);
    }

    @Override
    public void save(ApiToken token) {
        synchronized (lock) {
            tokens.write(token.id().value(), token);
        }
    }

    @Override
    public Optional<ApiToken> findById(ApiTokenId id) {
        return tokens.read(id.value()).filter(token -> token.id().equals(id));
    }

    @Override
    public Optional<ApiToken> findByHash(String tokenHash) {
        return tokens.readAll().stream().filter(token -> token.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public List<ApiToken> findByUser(UserId userId) {
        return tokens.readAll().stream().filter(token -> token.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(ApiTokenId id) {
        synchronized (lock) {
            tokens.delete(id.value());
        }
    }

    @Override
    public void recordUse(ApiTokenId id, Instant at) {
        synchronized (lock) {
            findById(id).ifPresent(token -> tokens.write(id.value(), token.usedAt(at)));
        }
    }
}
