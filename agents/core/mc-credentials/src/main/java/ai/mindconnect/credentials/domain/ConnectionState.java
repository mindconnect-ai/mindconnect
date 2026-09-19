package ai.mindconnect.credentials.domain;

/**
 * Whether a connection can still be used. A form-filled connection is
 * {@link #CONNECTED} from the moment it is saved; an OAuth one can go stale
 * while nobody is looking, and a tool that is refused says so rather than
 * failing the same way on every call.
 */
public enum ConnectionState {

    /** Usable as far as anybody knows. */
    CONNECTED,

    /** The token ran out and could not be refreshed — the user has to connect again. */
    EXPIRED,

    /** The last use was refused: a changed password, a revoked app. */
    ERROR
}
