package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Views in a row each: the contract every store keeps, and what the file
 * could not — parallel writers of one user's views, the namespace of the
 * moment, and the file's views taken over once.
 */
class PgViewStoreTest extends ViewStoreContract {

    private static final UserId ME = UserId.of("me");

    @TempDir
    Path dir;

    private Sql sql;
    private final AtomicReference<Namespace> namespace = new AtomicReference<>(new Namespace("local"));
    private PgViewStore store;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh(PgViewStore.TABLE, PgViewStore.IMPORTS);
        store = new PgViewStore(sql, namespace::get).initSchema();
    }

    @Override
    protected ViewStore store() {
        return store;
    }

    private FileViewStore files() {
        return new FileViewStore(() -> dir, namespace::get, new ObjectMapper().findAndRegisterModules());
    }

    private static StoredView view(UserId owner, String title, String at) {
        return new StoredView(ViewId.saved(), "agent", owner, title, ViewState.EMPTY,
                Map.of("entries", "email.privat\tINBOX\t7\n"), Instant.parse(at));
    }

    @Test
    void the_namespace_is_the_one_of_the_moment() {
        StoredView here = view(ME, "Werbung", "2026-09-23T10:00:00Z");
        store.save(here);

        namespace.set(new Namespace("elsewhere"));
        assertThat(store.load(ME, here.id())).isEmpty();
        assertThat(store.list(ME, null)).isEmpty();
        store.save(new StoredView(here.id(), "agent", ME, "Anderswo", ViewState.EMPTY, Map.of(), null));
        store.delete(ME, here.id());

        namespace.set(new Namespace("local"));
        assertThat(store.load(ME, here.id())).contains(here);
    }

    @Test
    void parallel_writers_of_one_users_views_all_survive() throws Exception {
        int writers = 16;
        int rounds = 10;
        List<ViewId> ids = new ArrayList<>();
        for (int i = 0; i < writers; i++) ids.add(ViewId.saved());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                ViewId id = ids.get(i);
                String title = "List " + i;
                done.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    // What mail_list_add does: read the view, add to it, save it — every round.
                    for (int round = 0; round < rounds; round++) {
                        StoredView current = store.load(ME, id).orElse(new StoredView(id, "agent", ME, title,
                                ViewState.EMPTY, Map.of(), null));
                        store.save(new StoredView(id, "agent", ME, title,
                                current.state().tick(List.of("email.privat:" + round)), current.data(), null));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<StoredView> all = store.list(ME, "agent");
        assertThat(all).hasSize(writers);
        assertThat(all).allSatisfy(v -> assertThat(v.state().selected()).hasSize(rounds));
    }

    @Test
    void the_files_views_are_taken_over_once_and_the_file_stays() {
        FileViewStore files = files();
        StoredView older = view(ME, "Werbung", "2026-09-23T10:00:00Z");
        StoredView newer = view(ME, "Rechnungen", "2026-09-23T11:00:00Z");
        files.save(older);
        files.save(newer);
        PgViewStore importing = new PgViewStore(sql, namespace::get, files);

        assertThat(importing.list(ME, null)).containsExactly(newer, older);
        assertThat(Files.exists(dir.resolve("local/mail-views/me.json"))).isTrue();

        // Deleted here, not brought back from the file — not by this store, not by the next one.
        importing.delete(ME, older.id());
        importing.delete(ME, newer.id());
        assertThat(importing.list(ME, null)).isEmpty();
        assertThat(new PgViewStore(sql, namespace::get, files).list(ME, null)).isEmpty();
        assertThat(files.list(ME, null)).hasSize(2);
    }

    @Test
    void a_save_before_any_read_imports_first() {
        FileViewStore files = files();
        StoredView kept = view(ME, "Werbung", "2026-09-23T10:00:00Z");
        files.save(kept);
        PgViewStore importing = new PgViewStore(sql, namespace::get, files);

        importing.save(view(ME, "Neu", "2026-09-23T12:00:00Z"));

        assertThat(importing.list(ME, null)).extracting(StoredView::title).containsExactly("Neu", "Werbung");
    }

    @Test
    void a_user_the_table_already_knows_takes_nothing_from_the_file() {
        StoredView inTable = view(ME, "Aus der Tabelle", "2026-09-23T10:00:00Z");
        store.save(inTable);
        FileViewStore files = files();
        files.save(view(ME, "Aus der Datei", "2026-09-23T11:00:00Z"));

        assertThat(new PgViewStore(sql, namespace::get, files).list(ME, null)).containsExactly(inTable);
    }

    @Test
    void the_file_is_imported_per_namespace() {
        FileViewStore files = files();
        StoredView local = view(ME, "Lokal", "2026-09-23T10:00:00Z");
        files.save(local);
        namespace.set(new Namespace("acme"));
        StoredView acme = view(ME, "Acme", "2026-09-23T10:00:00Z");
        files.save(acme);
        PgViewStore importing = new PgViewStore(sql, namespace::get, files);

        assertThat(importing.list(ME, null)).containsExactly(acme);
        namespace.set(new Namespace("local"));
        assertThat(importing.list(ME, null)).containsExactly(local);
    }

    @Test
    void callers_racing_to_the_first_read_import_once() throws Exception {
        FileViewStore files = files();
        files.save(view(ME, "Werbung", "2026-09-23T10:00:00Z"));
        files.save(view(ME, "Rechnungen", "2026-09-23T11:00:00Z"));

        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<List<StoredView>>> seen = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                // A store each, so no process-local memory stands in for the marker row.
                PgViewStore importing = new PgViewStore(sql, namespace::get, files);
                seen.add(pool.submit(() -> {
                    start.await();
                    return importing.list(ME, null);
                }));
            }
            start.countDown();
            for (Future<List<StoredView>> f : seen) {
                assertThat(f.get(30, TimeUnit.SECONDS)).extracting(StoredView::title)
                        .containsExactly("Rechnungen", "Werbung");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(sql.scalar("SELECT count(*) FROM " + PgViewStore.IMPORTS, Long.class)).isEqualTo(1L);
    }
}
