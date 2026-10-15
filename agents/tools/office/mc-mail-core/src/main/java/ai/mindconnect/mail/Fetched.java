package ai.mindconnect.mail;

import java.time.Instant;
import java.util.Objects;

/**
 * A value and how the store came by it: when, and whether it is fresh.
 *
 * <p>A store that asks the provider answers {@link Freshness#LIVE} and now.
 * One that answers from a local index says so — {@link Freshness#CACHED}, or
 * {@link Freshness#STALE} when the index has not heard from the provider for
 * longer than it should have. The screen can then say "as of 14:02" or show
 * a row as being refreshed; without this the age of a listing is invisible.
 *
 * @param value     what was fetched
 * @param at        when the store fetched it — for a cached value, when the
 *                  cache did, not when it was asked
 * @param freshness where it came from
 */
public record Fetched<T>(T value, Instant at, Freshness freshness) {

    public enum Freshness { LIVE, CACHED, STALE }

    public Fetched {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(freshness, "freshness");
    }

    /** Straight from the provider, a moment ago. */
    public static <T> Fetched<T> live(T value) {
        return new Fetched<>(value, Instant.now(), Freshness.LIVE);
    }

    public <R> Fetched<R> map(java.util.function.Function<T, R> f) {
        return new Fetched<>(f.apply(value), at, freshness);
    }
}
