package ai.mindconnect.agent.registry.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.common.Versions;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link RegistrySourceRepository} on Postgres: one row of
 * {@code mc_registry_source} per configured registry, keyed by
 * {@code (namespace, id)}, with the name beside the document for the order
 * the screen lists them in.
 *
 * <p>Bound to one namespace: every row it writes carries it, every statement
 * matches it. The {@code namespace} column is what the namespace purge finds
 * the rows by.
 *
 * <p>An installation that ran on files before keeps its registries:
 * {@link #importFiles} reads what the file store wrote, once, into a
 * namespace that has no row yet.
 */
public final class PgRegistrySourceRepository implements RegistrySourceRepository {

    private static final Logger log = LoggerFactory.getLogger(PgRegistrySourceRepository.class);

    private final DocumentTable<RegistrySource> sources;
    private final Namespace namespace;
    private final ObjectMapper mapper;

    public PgRegistrySourceRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgRegistrySourceRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.mapper = sql.json().mapper();
        this.sources = DocumentTable.of(RegistrySource.class)
                .table("mc_registry_source")
                .partitionKey("namespace", "TEXT", s -> namespace.value())
                .id("id", "TEXT", s -> s.id().value())
                .requiredColumn("name", "TEXT", RegistrySource::name)
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgRegistrySourceRepository initSchema() {
        sources.createSchema();
        return this;
    }

    /**
     * Imports the registries a file store kept in {@code directory} — one
     * {@code <id>.json} each, as {@code FileRegistrySourceRepository} writes
     * them — when this namespace has no row yet. Once: after the first import,
     * or once anything was saved here, the files are not looked at again. They
     * stay where they are; going back to files finds them as they were.
     *
     * <p>A file that is not a registry is skipped with a warning rather than
     * holding up the start. A file without an {@code id} gets its file name,
     * as a shipped registry does.
     *
     * @return how many registries were imported
     */
    public int importFiles(Path directory) {
        if (directory == null || !Files.isDirectory(directory)
                || sources.exists("WHERE namespace = ?", namespace.value())) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the registry files in " + directory, e);
        }
        ObjectReader reader = mapper.readerFor(RegistrySource.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        int imported = 0;
        for (Path file : files) {
            try {
                JsonNode tree = mapper.readTree(file.toFile());
                if (!(tree instanceof ObjectNode object)) {
                    throw new IllegalArgumentException("not a JSON object");
                }
                if (!object.hasNonNull("id")) {
                    String name = file.getFileName().toString();
                    object.put("id", name.substring(0, name.length() - ".json".length()));
                }
                RegistrySource source = reader.readValue(object);
                if (sources.insert(source)) {
                    imported++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Skipped the registry file {} of namespace '{}': {}",
                        file, namespace.value(), e.getMessage());
            }
        }
        if (imported > 0) {
            log.info("Imported {} registry source(s) of namespace '{}' from {} into Postgres; the files are kept",
                    imported, namespace.value(), directory);
        }
        return imported;
    }

    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    @Override
    public RegistrySource save(RegistrySource source) {
        return sources.compute(namespace.value(), source.id().value(), current ->
                source.withVersion(Versions.next(
                        current.map(RegistrySource::version).orElse(null), source.version(),
                        "RegistrySource", source.id().value())));
    }

    @Override
    public Optional<RegistrySource> findById(RegistrySourceId id) {
        return sources.findById(namespace.value(), id.value());
    }

    @Override
    public List<RegistrySource> findAll() {
        return sources.find("WHERE namespace = ? ORDER BY lower(name), id", namespace.value());
    }

    @Override
    public void deleteById(RegistrySourceId id) {
        sources.deleteById(namespace.value(), id.value());
    }
}
