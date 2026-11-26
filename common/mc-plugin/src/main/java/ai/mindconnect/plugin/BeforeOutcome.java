package ai.mindconnect.plugin;

/**
 * What a {@link BeforeHook} decided: the run goes on — with the context it
 * was given or with a changed one — or it ends here with a result, and what
 * the point is for never happens.
 */
public sealed interface BeforeOutcome<C, R> {

    /** The run goes on with this context. */
    record Proceed<C, R>(C context) implements BeforeOutcome<C, R> { }

    /** The run ends with this result. */
    record Stop<C, R>(R result) implements BeforeOutcome<C, R> { }

    static <C, R> BeforeOutcome<C, R> proceed(C context) {
        return new Proceed<>(context);
    }

    static <C, R> BeforeOutcome<C, R> stop(R result) {
        return new Stop<>(result);
    }
}
