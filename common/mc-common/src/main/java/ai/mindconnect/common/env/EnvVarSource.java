package ai.mindconnect.common.env;

import java.util.function.Supplier;

/**
 * A source of variables a plugin contributes to its host's {@link EnvVarResolver}.
 * Wrapped rather than contributed as a resolver, because the host provides the
 * resolver itself: a type is either one shared service or one of many
 * contributions, never both.
 *
 * <p>The host asks the {@linkplain EnvVarResolver#personal() personal} sources
 * first, then the shared ones in the order contributed, the process environment
 * last — so a user's own key wins over the namespace's whatever order the
 * plugins were installed in.
 *
 * <p>The source is made when the host first resolves a variable, not when it
 * is contributed: a source reads a store, and the store is made from services
 * that are complete only once every plugin is installed.
 */
public record EnvVarSource(Supplier<EnvVarResolver> resolver) {
}
