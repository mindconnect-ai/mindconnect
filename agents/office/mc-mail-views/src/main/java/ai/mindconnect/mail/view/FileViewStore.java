package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The views as one JSON file per user, under
 * {@code <dataDir>/<namespace>/mail-views/<user>.json} — the same shape as
 * the built-in todo list keeps its book.
 *
 * <p>Written to a temporary file and moved over the real one, so a crash
 * mid-write leaves the old file whole. A user's views are dozens, not
 * thousands; one file read per request is nothing beside the mail behind it.
 */
public final class FileViewStore implements ViewStore {

    private final Supplier<Path> dataDir;
    private final Supplier<Namespace> namespace;
    private final ObjectMapper json;

    public FileViewStore(Supplier<Path> dataDir, Supplier<Namespace> namespace, ObjectMapper json) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<StoredView> load(UserId user, ViewId id) {
        Record found = read(user).get(id.value());
        return Optional.ofNullable(found).map(r -> r.toView(user, id));
    }

    @Override
    public void save(StoredView view) {
        Map<String, Record> all = read(view.owner());
        all.put(view.id().value(), Record.of(view));
        write(view.owner(), all);
    }

    @Override
    public void delete(UserId user, ViewId id) {
        Map<String, Record> all = read(user);
        if (all.remove(id.value()) != null) write(user, all);
    }

    @Override
    public List<StoredView> list(UserId user, String kind) {
        List<StoredView> out = new ArrayList<>();
        for (Map.Entry<String, Record> entry : read(user).entrySet()) {
            if (kind == null || kind.equals(entry.getValue().kind)) {
                out.add(entry.getValue().toView(user, ViewId.of(entry.getKey())));
            }
        }
        out.sort(Comparator.comparing(StoredView::updatedAt).reversed());
        return out;
    }

    Path file(UserId user) {
        return dataDir.get().resolve(namespace.get().value()).resolve("mail-views")
                .resolve(safe(user.value()) + ".json");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._@-]", "_");
    }

    private Map<String, Record> read(UserId user) {
        Path file = file(user);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Record> all = json.readValue(text, new TypeReference<LinkedHashMap<String, Record>>() { });
            return all == null ? new LinkedHashMap<>() : all;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the mail views of " + user.value(), e);
        }
    }

    private void write(UserId user, Map<String, Record> all) {
        Path file = file(user);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(all),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the mail views of " + user.value(), e);
        }
    }

    /** The JSON shape — plain fields, so the file reads without the domain types. */
    static final class Record {
        public String kind;
        public String title;
        public List<String> selected = List.of();
        public String query;
        public int page = 1;
        public Map<String, String> extra = Map.of();
        public Map<String, String> data = Map.of();
        public Instant updatedAt;

        static Record of(StoredView view) {
            Record r = new Record();
            r.kind = view.kind();
            r.title = view.title();
            r.selected = new ArrayList<>(view.state().selected());
            r.query = view.state().query();
            r.page = view.state().page();
            r.extra = view.state().extra();
            r.data = view.data();
            r.updatedAt = view.updatedAt();
            return r;
        }

        StoredView toView(UserId owner, ViewId id) {
            return new StoredView(id, kind, owner, title,
                    new ViewState(new LinkedHashSet<>(selected == null ? List.of() : selected), query, page, extra),
                    data, updatedAt);
        }
    }
}
