package ai.mindconnect.agent.tool;

/**
 * Tries a connection out, without a tool call — the button beside a freshly
 * typed password.
 *
 * <p>Offered by the source that declared the {@link ConnectionSpec}
 * ({@link ToolFactory#connectionTester()}, {@link MultiToolProvider#connectionTester()}),
 * not by each tool: a mailbox is proved once, not five times. Implementations
 * do the cheapest thing that proves the account works — sign in and count a
 * folder, ask the API who the user is — and answer quickly, because someone is
 * waiting for the toast.
 *
 * <p>A failure is a {@link ConnectionTest#failed(String)} with the reason, not
 * an exception; the runtime treats an exception as a failure of the test
 * itself.
 */
@FunctionalInterface
public interface ConnectionTester {

    ConnectionTest test(ToolConnection connection);
}
