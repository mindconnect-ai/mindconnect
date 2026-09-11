package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserRepository} on the file system: one JSON document per user under
 * {@code <storageDir>/<namespace>/system/users/}.
 *
 * <p>A user id is whatever the identity provider calls the user — mixed case,
 * {@code @}, dots — so it is not a file name as it stands. The file is named
 * by a readable, lower-cased form of the id plus a short hash of the exact id:
 * {@code Alice} and {@code alice} get two files even on a case-insensitive
 * file system, and the name still tells a human whose file it is.
 */
public class FileUserRepository implements UserRepository {

    private final FileDocuments<User> users;

    public FileUserRepository(Path storageDir, Namespace namespace) {
        Objects.requireNonNull(namespace, "namespace");
        this.users = new FileDocuments<>(
                storageDir.resolve(namespace.value()).resolve("system").resolve("users"), User.class);
    }

    @Override
    public Optional<User> findById(UserId id) {
        return users.read(fileKey(id)).filter(user -> user.id().equals(id));
    }

    @Override
    public List<User> findAll() {
        return users.readAll();
    }

    @Override
    public void save(User user) {
        users.write(fileKey(user.id()), user);
    }

    /** The file name (without {@code .json}) a user id is stored under. */
    static String fileKey(UserId id) {
        String readable = id.value().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (readable.length() > 64) {
            readable = readable.substring(0, 64);
        }
        return readable + "-" + sha256(id.value()).substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
