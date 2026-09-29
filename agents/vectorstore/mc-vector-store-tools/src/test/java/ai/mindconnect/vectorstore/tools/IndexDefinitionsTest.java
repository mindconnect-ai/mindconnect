package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stores say what belongs together, indexes where it is kept: chat uploads in
 * a table of their own, a namespace's index in a table or a database of its
 * own, files when there is no pgvector.
 */
class IndexDefinitionsTest {

    private static final Namespace NS = new Namespace("idx-defs");
    private static final String OWN_TABLE = "mc_embedding_it_bigkb";
    private static final EntityRef FILE_1 = EntityRef.of(EntityType.FILE, EntityRef.FILE_STORE, "file-1");
    private static final List<VectorStore.TextChunk> TEXT =
            List.of(new VectorStore.TextChunk("podman is a container engine", Map.of()));

    @TempDir
    Path dir;

    private DataSource db;

    @AfterEach
    void tearDown() {
        if (db != null) {
            Sql sql = Sql.of(db);
            sql.execute("DROP TABLE IF EXISTS " + OWN_TABLE + "; DROP TABLE IF EXISTS " + OWN_TABLE + "_field");
            TestDb.forget(db, NS);
        }
    }

    @Test
    void chatUploadsLiveInATableOfTheirOwn() {
        db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();
        stores.registry(NS).saveTemplate(new VectorStoreTemplate("chat-uploads", "embeddings", null, Map.of()));

        VectorStore chat = stores.open(NS, "session-s1", "chat-uploads", VectorStoreInstance.Scope.SESSION, "s1", "alice");
        VectorStore kb = stores.open(NS, "kb", null, VectorStoreInstance.Scope.GLOBAL, null);
        chat.put(FILE_1, null, "1", TEXT);
        kb.put(kb.documentRef("notes"), null, "1", TEXT);

        Sql sql = Sql.of(db);
        assertThat(sql.scalar("SELECT count(*) FROM mc_embedding_chat WHERE namespace = ?", Long.class, NS.value())).isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM mc_embedding WHERE namespace = ?", Long.class, NS.value())).isEqualTo(1L);
        assertThat(stores.indexOf(NS, chat.settings())).isEqualTo("chat-uploads");
        assertThat(stores.indexLocation(NS, "chat-uploads")).endsWith("— table mc_embedding_chat");
        assertThat(chat.search("container", 5)).hasSize(1);
        assertThat(kb.indexed(FILE_1)).as("another index: the file would be embedded there again").isFalse();
    }

    @Test
    void aTemplateCanNameAnIndexInATableOfItsOwn() {
        db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();
        stores.saveIndex(NS, new IndexDefinition("big-kb", "pgvector", OWN_TABLE, null, null, null, null, "one large base"));
        stores.registry(NS).saveTemplate(new VectorStoreTemplate("handbooks", "embeddings", null, Map.of()).withIndex("big-kb"));

        VectorStore store = stores.open(NS, "manuals", "handbooks", VectorStoreInstance.Scope.GLOBAL, null);
        store.put(FILE_1, null, "1", TEXT);

        assertThat(store.settings().index()).isEqualTo("big-kb");
        assertThat(Sql.of(db).scalar("SELECT count(*) FROM " + OWN_TABLE + " WHERE namespace = ?", Long.class, NS.value()))
                .isEqualTo(1L);
        assertThat(stores.indexes(NS)).extracting(IndexDefinition::name).containsExactly("default", "chat-uploads", "big-kb");
        assertThat(stores.isBuiltIn(NS, "default")).isTrue();
        assertThat(stores.isBuiltIn(NS, "big-kb")).isFalse();
        assertThatThrownBy(() -> stores.index(NS, "nowhere")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNamespaceCanMoveItsIndexIntoADatabaseOfItsOwn_andItsDeletionReachesThere() {
        db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        DefaultVectorStores stores = (DefaultVectorStores) VectorStores.fromEnvironment(
                TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();
        // The test database stands in for the tenant's own — reached by URL, not through the application's pool.
        stores.saveIndex(NS, new IndexDefinition("default", "pgvector", OWN_TABLE, TestDb.URL + "?user=postgres",
                "postgres", "test", null, null));

        VectorStore kb = stores.open(NS, "kb", null, VectorStoreInstance.Scope.GLOBAL, null);
        kb.put(kb.documentRef("notes"), null, "1", TEXT);

        assertThat(stores.indexLocation(NS)).isEqualTo("Postgres (pgvector), " + TestDb.URL + " — table " + OWN_TABLE);
        assertThat(stores.isBuiltIn(NS, "default")).isFalse();
        Sql sql = Sql.of(db);
        assertThat(sql.scalar("SELECT count(*) FROM " + OWN_TABLE + " WHERE namespace = ?", Long.class, NS.value())).isEqualTo(1L);

        stores.purge(NS);
        assertThat(sql.scalar("SELECT count(*) FROM " + OWN_TABLE + " WHERE namespace = ?", Long.class, NS.value())).isZero();
    }

    @Test
    void onFilesChatUploadsGetADirectoryOfTheirOwn() {
        DefaultVectorStores stores = new DefaultVectorStores(
                new VectorStoreTemplate("default", "embeddings", null, Map.of()), dir,
                (config, texts) -> texts.stream().map(t -> new float[]{1f, 0f}).toList(),
                new ai.mindconnect.llm.port.out.LlmConfigRepository() {
                    @Override public java.util.Optional<ai.mindconnect.llm.domain.LlmConfig> findById(ai.mindconnect.llm.domain.LlmConfigId id) { return java.util.Optional.empty(); }
                    @Override public java.util.Optional<ai.mindconnect.llm.domain.LlmConfig> findByName(String name) {
                        return java.util.Optional.of(ai.mindconnect.llm.domain.LlmConfig.lmStudio(name, "fake", "http://unused"));
                    }
                    @Override public List<ai.mindconnect.llm.domain.LlmConfig> findAll() { return List.of(); }
                    @Override public void save(ai.mindconnect.llm.domain.LlmConfig config) { }
                    @Override public void deleteById(ai.mindconnect.llm.domain.LlmConfigId id) { }
                }, null);
        stores.registry(NS).saveTemplate(new VectorStoreTemplate("chat-uploads", "embeddings", null, Map.of()));
        VectorStore chat = stores.open(NS, "session-s1", "chat-uploads", VectorStoreInstance.Scope.SESSION, "s1", "alice");
        chat.put(FILE_1, null, "1", TEXT);

        assertThat(dir.resolve(NS.value()).resolve("embeddings-chat-uploads/entries")).isDirectory();
        assertThat(dir.resolve(NS.value()).resolve("embeddings")).doesNotExist();
        assertThat(stores.indexLocation(NS, "chat-uploads")).endsWith("embeddings-chat-uploads — file persistence");
    }

    @Test
    void aStoreFromBeforeIndexesFollowsItsTemplatesName() {
        DefaultVectorStores stores = new DefaultVectorStores(
                new VectorStoreTemplate("default", "embeddings", null, Map.of()), dir, null, null, null);
        VectorStoreInstance chat = new VectorStoreInstance("session-s1", "chat-uploads", "embeddings", null, Map.of(),
                VectorStoreInstance.Scope.SESSION, "s1", "alice", Instant.now());
        VectorStoreInstance kb = new VectorStoreInstance("kb", "default", "embeddings", null, Map.of(),
                VectorStoreInstance.Scope.GLOBAL, null, null, Instant.now());

        assertThat(stores.indexOf(NS, chat)).isEqualTo("chat-uploads");
        assertThat(stores.indexOf(NS, kb)).isEqualTo("default");
    }

    @Test
    void definitionsAreChecked() {
        assertThatThrownBy(() -> new IndexDefinition("Bad Name", "pgvector", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexDefinition("x", "mongo", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexDefinition("x", "pgvector", "t; drop", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexDefinition("x", "file", null, null, null, null, "../up", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
