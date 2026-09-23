package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A small document store of the extension's own — one per kind of thing it
 * keeps (scenarios, adventures), bound to one namespace like every store of
 * the host. Three adapters on the persistence the host runs on: JSON files
 * under {@code <namespace>/ext/demo-dungeon/<kind>/}, rows in the schema
 * {@code ext_demo_dungeon}, or a map. What {@code contributes.persistence}
 * declares: a corner of its own, never the core's tables.
 *
 * <p>Public because the host's namespace routing puts a JDK proxy in front
 * of it, and a proxy handler from another package can only call a public
 * interface.
 */
public interface DemoStore<T> {

    Optional<T> find(String id);

    List<T> all();

    void save(T value);

    void delete(String id);

    /** JSON documents under the namespace's directory, one file per record, locked per document by the file repo. */
    static <T> DemoStore<T> onFiles(Path dataDir, Namespace namespace, ObjectMapper mapper, Class<T> type,
                                    String kind, Function<T, String> id) {
        FileRepo repo = FileRepo.open(dataDir, namespace.value());
        String dir = "ext/demo-dungeon/" + kind;
        Documents<String, T> documents = Documents.of(type)
                .path((String key) -> dir + "/" + key + ".json")
                .prettyPrint()
                .build(repo, mapper);
        return new DemoStore<>() {
            @Override public Optional<T> find(String key) { return documents.find(key); }
            @Override public List<T> all() { return documents.findAll(dir); }
            @Override public void save(T value) { documents.put(id.apply(value), value); }
            @Override public void delete(String key) { documents.delete(key); }
        };
    }

    /** Rows of {@code ext_demo_dungeon.<kind>}, keyed by {@code (namespace, id)}; the schema is created if missing. */
    static <T> DemoStore<T> onPostgres(Sql sql, Namespace namespace, Class<T> type, String kind, Function<T, String> id) {
        String schema = "ext_demo_dungeon";
        DocumentTable<T> table = DocumentTable.of(type)
                .table(schema + "." + kind)
                .partitionKey("namespace", "TEXT", t -> namespace.value())
                .id("id", "TEXT", id::apply)
                .build(sql);
        sql.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        table.createSchema();
        return new DemoStore<>() {
            @Override public Optional<T> find(String key) { return table.findById(namespace.value(), key); }
            @Override public List<T> all() { return table.find("WHERE namespace = ? ORDER BY id", namespace.value()); }
            @Override public void save(T value) { table.save(value); }
            @Override public void delete(String key) { table.deleteById(namespace.value(), key); }
        };
    }

    /** A map — for a runtime that keeps nothing. */
    static <T> DemoStore<T> inMemory(Function<T, String> id) {
        Map<String, T> map = new ConcurrentHashMap<>();
        return new DemoStore<>() {
            @Override public Optional<T> find(String key) { return Optional.ofNullable(map.get(key)); }
            @Override public List<T> all() {
                return map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
            }
            @Override public void save(T value) { map.put(id.apply(value), value); }
            @Override public void delete(String key) { map.remove(key); }
        };
    }

    /** A store's records in a stable order for the screen. */
    static <T> List<T> sorted(List<T> values, Comparator<T> order) {
        return values.stream().sorted(order).toList();
    }
}
