package ai.mindconnect.jdbc;

/**
 * A query parameter that must reach Postgres as {@code jsonb}. Wrap the
 * rendered JSON in this and {@link Sql} binds it with the right type, so the
 * statement needs no {@code ?::jsonb} cast.
 */
public record Jsonb(String json) {

    public static Jsonb of(String json) {
        return new Jsonb(json);
    }
}
