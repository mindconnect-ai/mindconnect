package ai.mindconnect.credentials.oauth;

/**
 * An authorization attempt did not work — the provider refused, the callback
 * did not match, the refresh token is spent.
 *
 * <p>Its message is written for the person who has to act on it and never
 * carries a token or a code.
 */
public class OAuthException extends RuntimeException {

    public OAuthException(String message) {
        super(message);
    }

    public OAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
