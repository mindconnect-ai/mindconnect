package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A view as the store holds it: the same shape for every kind, and the kind
 * says how to read it. The store knows nothing about kinds; the factory for
 * the kind turns this back into a view.
 *
 * @param data what the view keeps beside its state — {@link MailListView#data()}
 */
public record StoredView(
        ViewId id,
        String kind,
        UserId owner,
        String title,
        ViewState state,
        Map<String, String> data,
        Instant updatedAt
) {

    public StoredView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        state = state == null ? ViewState.EMPTY : state;
        data = data == null ? Map.of() : Map.copyOf(data);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static StoredView of(MailListView view) {
        return new StoredView(view.id(), view.kind(), view.owner(), view.title(), view.state(),
                view.data(), Instant.now());
    }

    public StoredView titled(String title) {
        return new StoredView(id, kind, owner, title, state, data, Instant.now());
    }
}
