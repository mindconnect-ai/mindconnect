package ai.mindconnect.agent.migration;

/**
 * A change to stored data that a new version of mindconnect needs: what was
 * written one way is written the way the code now reads it. Kept as a class
 * of its own, so that it stays when the code that wrote the old form is gone.
 *
 * <p>A migration runs once per namespace, in the namespace the current call
 * works in, and is logged with that namespace's data. It must run again
 * without harm when an earlier run broke off: what it finds moved already,
 * it leaves.
 */
public interface Migration {

    /** Unique among all migrations; the log knows a migration by it. */
    String id();

    /**
     * The version of mindconnect it came with, such as {@code 0.8.5}.
     * Migrations run in the order of their versions, those of one version in
     * the order of their ids.
     */
    String version();

    /**
     * Migrates the namespace the current call works in.
     *
     * @return what it did, in a line, for the log
     * @throws RuntimeException when it could not do all of it; it is then not
     *                          logged and runs again on the next start
     */
    String migrate();
}
