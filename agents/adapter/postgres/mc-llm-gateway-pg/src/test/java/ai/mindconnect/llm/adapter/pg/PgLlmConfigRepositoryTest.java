package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Against a real Postgres; skipped when none answers on 5433
 * ({@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}).
 */
class PgLlmConfigRepositoryTest {

    private PgLlmConfigRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_llm_config");
        repo = new PgLlmConfigRepository(sql).initSchema();
    }

    @Test
    void aConfigSurvivesTheRoundTripUnchanged() {
        LlmConfig config = new LlmConfig(UUID.randomUUID(), "claude", LlmProvider.ANTHROPIC,
                "claude-sonnet-5", "https://api.anthropic.com", "sk-secret", 0.3, 8192,
                Map.of("top_p", 0.9, "stop", List.of("END")), 200_000, false, null, null, null,
                LlmConfigType.CHAT);
        repo.save(config);

        assertThat(repo.findById(config.id())).contains(config);
        assertThat(repo.findByName("claude")).contains(config);
    }

    @Test
    void savingAgainReplacesTheConfigAndItsName() {
        LlmConfig first = LlmConfig.lmStudio("local", "qwen", "http://localhost:1234");
        repo.save(first);
        LlmConfig renamed = LlmConfig.fromJson(first.id(), "local-qwen", first.provider(), "qwen-2",
                first.baseUrl(), first.apiKey(), 0.1, 1024, Map.of(), null, false, null, null, null, null);
        repo.save(renamed);

        assertThat(repo.findAll()).containsExactly(renamed);
        assertThat(repo.findByName("local")).isEmpty();
        assertThat(repo.findByName("local-qwen")).contains(renamed);
    }

    @Test
    void findAllIsSortedByNameAndDeleteRemovesOne() {
        LlmConfig b = LlmConfig.ollama("b-ollama", "llama3", "http://localhost:11434");
        LlmConfig a = LlmConfig.claude("a-claude", "claude-sonnet-5", "key");
        repo.save(b);
        repo.save(a);

        assertThat(repo.findAll()).extracting(LlmConfig::name).containsExactly("a-claude", "b-ollama");
        repo.deleteById(b.id());
        assertThat(repo.findAll()).containsExactly(a);
        assertThat(repo.findById(b.id())).isEmpty();
        repo.deleteById(b.id()); // deleting what is gone is not an error
    }

    @Test
    void anAliasResolvesThroughThePortsDefaultMethod() {
        LlmConfig target = LlmConfig.claude("claude", "claude-sonnet-5", "key");
        repo.save(target);
        repo.save(LlmConfig.alias("agent-default", "claude"));

        assertThat(repo.findResolvedByName("agent-default")).get()
                .extracting(LlmConfig::model).isEqualTo("claude-sonnet-5");
        assertThat(repo.findResolvedByName("nobody")).isEmpty();
    }

    @Test
    void initSchemaIsIdempotent() {
        repo.save(LlmConfig.alias("x", "y"));
        new PgLlmConfigRepository(Sql.of(requirePostgres())).initSchema();
        assertThat(repo.findAll()).hasSize(1);
    }

    private static DataSource requirePostgres() {
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
