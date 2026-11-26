package ai.mindconnect.agent.scope;

/**
 * The one storage partition everything of the agents area lives in: a
 * directory name on disk, a column value in Postgres, a path segment in a
 * URL. In JSON it is its plain value, like every other id.
 */
public record Namespace(String value) {

    public static final Namespace DEFAULT = new Namespace("local");

    public Namespace {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Namespace must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
