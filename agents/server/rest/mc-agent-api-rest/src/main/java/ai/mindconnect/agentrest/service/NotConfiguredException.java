package ai.mindconnect.agentrest.service;

/**
 * A service capability this host application does not have configured —
 * e.g. vector stores in an agent server started without them. REST
 * controllers map this to {@code 503 Service Unavailable}; UI controllers
 * show the message. Keeping it a runtime signal (instead of hard bean
 * dependencies) lets every host boot with the same controller set.
 */
public class NotConfiguredException extends IllegalStateException {
    public NotConfiguredException(String capability) {
        super(capability + " is not configured in this application");
    }
}
