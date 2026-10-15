package ai.mindconnect.mail.view;

/** No view answers to that name for this user — deleted, never saved, or somebody else's. */
public class NoSuchViewException extends RuntimeException {

    public NoSuchViewException(ViewId id) {
        super("There is no list called \"" + id.value() + "\" any more.");
    }
}
