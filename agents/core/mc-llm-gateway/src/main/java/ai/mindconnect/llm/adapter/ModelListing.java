package ai.mindconnect.llm.adapter;

import java.util.List;
import java.util.function.Predicate;

/**
 * What a model server answered when asked which models it serves: the
 * models, or the reason there are none — an unreachable host, a refused key,
 * an endpoint without a listing. A server that cannot be asked is an expected
 * answer, so a form shows {@link #error()} as a hint instead of failing.
 *
 * @param <M> the catalog's own model record: LM Studio reports more about a
 *            model than a {@code /v1/models} listing does
 */
public record ModelListing<M>(String baseUrl, List<M> models, String error) {

    public boolean available() {
        return error == null;
    }

    /** An answer that could not be had, with the reason to show in the form. */
    public static <M> ModelListing<M> unavailable(String baseUrl, String error) {
        return new ModelListing<>(baseUrl, List.of(), error);
    }

    /** The first listed model that matches, or {@code null} — what the form asks after a pick. */
    public M find(Predicate<M> match) {
        return models.stream().filter(match).findFirst().orElse(null);
    }
}
