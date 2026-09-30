package ai.mindconnect.mail.index;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The windows as a row per mail: the contract, the embedding that outlives
 * every save of its head, the namespace of the moment, a database without
 * pgvector, and the index running on it.
 */
class PgMailIndexStoreTest extends MailIndexStoreContract {

    private Sql sql;
    private final AtomicReference<String> namespace = new AtomicReference<>("local");
    private PgMailIndexStore store;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh(PgMailIndexStore.WINDOWS, PgMailIndexStore.HEADS);
        store = new PgMailIndexStore(sql, namespace::get).initSchema();
    }

    @Override
    protected MailIndexStore store() {
        return store;
    }

    private boolean hasEmbeddingColumn() {
        return sql.scalar("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name = 'embedding'", Long.class, PgMailIndexStore.HEADS) == 1;
    }

    private void embed(String messageId, String vector) {
        sql.update("UPDATE " + PgMailIndexStore.HEADS + " SET embedding = ?::vector WHERE message_id = ?",
                vector, messageId);
    }

    private Optional<String> embedding(String ns, String messageId) {
        return sql.queryOne("SELECT embedding::text AS e FROM " + PgMailIndexStore.HEADS
                + " WHERE namespace = ? AND message_id = ? AND embedding IS NOT NULL",
                row -> row.string("e"), ns, messageId);
    }

    private long heads(String ns) {
        return sql.scalar("SELECT count(*) FROM " + PgMailIndexStore.HEADS + " WHERE namespace = ?", Long.class, ns);
    }

    @Test
    void every_head_is_a_row_of_its_own() {
        store.save(ME, window(INBOX, 3));

        assertThat(heads("local")).isEqualTo(3);
        assertThat(sql.scalar("SELECT count(*) FROM " + PgMailIndexStore.WINDOWS, Long.class)).isEqualTo(1L);
    }

    @Test
    void an_embedding_outlives_a_save_that_changes_and_reorders_the_window() {
        assumeTrue(hasEmbeddingColumn(), "pgvector is not installed in the test database");
        FolderWindow first = window(INBOX, 3);
        store.save(ME, first);
        embed("2", "[1,2,3]");
        embed("3", "[4,5,6]");

        // A new mail on top, head 3 read now, and a new total — every position moves.
        MailMessage arrived = new MailMessage("4", INBOX, "Mail 4", "Bob <bob@example.com>", List.of(),
                Instant.parse("2026-09-23T12:05:00Z"), false, false, List.of(), "preview 4", true);
        FolderWindow changed = first.with(arrived).replaced(first.head("3").withSeen(true));
        store.save(ME, changed);

        assertThat(store.load(ME, INBOX)).contains(changed);
        assertThat(store.load(ME, INBOX).orElseThrow().heads()).extracting(MailMessage::id)
                .containsExactly("4", "3", "2", "1");
        assertThat(embedding("local", "2")).contains("[1,2,3]");
        assertThat(embedding("local", "3")).contains("[4,5,6]");
        assertThat(embedding("local", "4")).isEmpty();
    }

    @Test
    void a_head_that_leaves_the_window_takes_its_row_and_embedding_with_it() {
        assumeTrue(hasEmbeddingColumn(), "pgvector is not installed in the test database");
        store.save(ME, window(INBOX, 3));
        embed("2", "[1,2,3]");

        store.save(ME, window(INBOX, 3).without(List.of("2")));

        assertThat(sql.scalar("SELECT count(*) FROM " + PgMailIndexStore.HEADS + " WHERE message_id = '2'",
                Long.class)).isZero();
        assertThat(heads("local")).isEqualTo(2);

        store.delete(ME, INBOX);
        assertThat(heads("local")).isZero();
        assertThat(store.windows(ME)).isEmpty();
    }

    @Test
    void a_window_emptied_keeps_its_row_and_loses_its_heads() {
        store.save(ME, window(INBOX, 3));
        FolderWindow empty = new FolderWindow(INBOX, List.of(), 0, Instant.parse("2026-09-23T13:00:00Z"), 1_000);

        store.save(ME, empty);

        assertThat(store.load(ME, INBOX)).contains(empty);
        assertThat(heads("local")).isZero();
    }

    @Test
    void the_namespace_is_the_one_of_the_moment() {
        FolderWindow here = window(INBOX, 2);
        store.save(ME, here);

        namespace.set("elsewhere");
        assertThat(store.load(ME, INBOX)).isEmpty();
        assertThat(store.windows(ME)).isEmpty();
        store.save(ME, window(INBOX, 5));
        assertThat(heads("elsewhere")).isEqualTo(5);
        store.save(ME, window(INBOX, 5).without(List.of("1", "2")));   // deletes heads here only
        store.delete(ME, INBOX);
        assertThat(heads("elsewhere")).isZero();

        namespace.set("local");
        assertThat(store.load(ME, INBOX)).contains(here);
        assertThat(heads("local")).isEqualTo(2);
    }

    @Test
    void without_pgvector_the_heads_go_without_embeddings_until_a_start_finds_it() {
        sql.execute("DROP TABLE " + PgMailIndexStore.HEADS);
        PgMailIndexStore without = new PgMailIndexStore(sql, namespace::get, db -> false).initSchema();
        assertThat(hasEmbeddingColumn()).isFalse();

        FolderWindow saved = window(INBOX, 3);
        without.save(ME, saved);
        assertThat(without.load(ME, INBOX)).contains(saved);

        // The next start finds the extension: the column arrives, the rows stay.
        new PgMailIndexStore(sql, namespace::get).initSchema();
        assumeTrue(hasEmbeddingColumn(), "pgvector is not installed in the test database");
        assertThat(without.load(ME, INBOX)).contains(saved);
        assertThat(embedding("local", "1")).isEmpty();
    }

    @Test
    void a_row_that_cannot_be_read_is_a_window_filled_again() {
        store.save(ME, window(INBOX, 2));
        sql.update("UPDATE " + PgMailIndexStore.HEADS + " SET doc = '{\"to\":7}'");

        assertThat(store.load(ME, INBOX)).isEmpty();
    }

    @Test
    void the_index_runs_on_it_as_on_the_files() {
        MailIndexTest.CountingStore mail = new MailIndexTest.CountingStore(2_500);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
        MailIndex index = new MailIndex(store, clock, 300, MailIndex.TTL);

        index.page(ME, mail, ODD, 0, 10, null);
        index.removed(ME, ODD, List.of("2500"));

        FolderWindow read = store.load(ME, ODD).orElseThrow();
        assertThat(read.size()).isEqualTo(299);
        assertThat(read.total()).isEqualTo(2_499);
        assertThat(read.heads().get(0).receivedAt()).isEqualTo(mail.receivedAt(2_499));
        assertThat(store.windows(ME)).containsExactly(new Location("email.privat", "Kunden/2026 & Co"));
    }
}
