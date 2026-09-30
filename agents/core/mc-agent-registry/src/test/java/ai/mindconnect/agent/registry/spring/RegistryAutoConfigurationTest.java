package ai.mindconnect.agent.registry.spring;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.registry.adapter.file.FileRegistrySourceRepository;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where the configured registries live follows {@code mindconnect.persistence}:
 * the files by default, Postgres — routed per namespace, the files imported
 * once, the file store never opened — on {@code postgres}.
 */
class RegistryAutoConfigurationTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                // What a Boot application registers, so @Value durations convert as they do there.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withConfiguration(AutoConfigurations.of(RegistryAutoConfiguration.class))
                .withPropertyValues("mindconnect.data.base-dir=" + dir);
    }

    @Test
    void by_default_the_registries_are_files() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RegistrySourceRepository.class))
                    .isInstanceOf(FileRegistrySourceRepository.class);
        });
    }

    @Test
    void on_postgres_each_namespace_has_its_rows_and_imports_its_files_once() throws Exception {
        Sql sql = Sql.of(testDb());
        sql.execute("DROP TABLE IF EXISTS mc_registry_source");
        writeRegistryFile("local", "acme-agents", "acme", "agents");
        writeRegistryFile("team", "team-catalog", "team", "catalog");
        ThreadBoundScope scope = ThreadBoundScope.strict();

        runner().withPropertyValues(
                        "mindconnect.persistence=postgres",
                        "mindconnect.registry.default-source=mindconnect-ai/mc-registry")
                .withBean(Sql.class, () -> sql)
                .withBean(ScopeSupplier.class, () -> scope)
                .run(context -> {
                    // The service counted its sources at start: in the start-up namespace, not unbound.
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RegistryService.class);
                    RegistrySourceRepository sources = context.getBean(RegistrySourceRepository.class);
                    assertThat(sources).isNotInstanceOf(FileRegistrySourceRepository.class);

                    assertThat(owners(scope, sources, "local")).containsExactly("acme");
                    assertThat(owners(scope, sources, "team")).containsExactly("team");
                    // No files, nothing imported: the default source is seeded instead.
                    assertThat(owners(scope, sources, "fresh")).containsExactly("mindconnect-ai");
                });

        assertThat(Files.exists(dir.resolve("local/system/registries/acme-agents.json"))).isTrue();
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .doesNotContain(".mc-partition.lock");
        }
    }

    private static List<String> owners(ThreadBoundScope scope, RegistrySourceRepository sources, String namespace) {
        return scope.runIn(Scope.of(new Namespace(namespace)),
                () -> sources.findAll().stream().map(RegistrySource::owner).toList());
    }

    private void writeRegistryFile(String namespace, String id, String owner, String repo) throws Exception {
        Path registries = Files.createDirectories(dir.resolve(namespace).resolve("system/registries"));
        Files.writeString(registries.resolve(id + ".json"), """
                {"id": "%s", "owner": "%s", "repo": "%s", "enabled": true, "version": 1}
                """.formatted(id, owner, repo));
    }

    /** A real Postgres, or the test is skipped (see the pg adapter tests for the container). */
    private static PGSimpleDataSource testDb() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres"));
        ds.setUser(System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"));
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
        return ds;
    }
}
