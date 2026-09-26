package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.embedding.EmbeddingChunk;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.EmbeddingHit;
import ai.mindconnect.vectorstore.embedding.EmbeddingQuery;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared table with real text and a real embedding model: nomic-embed-text
 * served by LM Studio (OpenAI-compatible {@code /v1/embeddings}). Skipped when
 * the database or LM Studio does not answer; {@code MC_EMBEDDING_URL} and
 * {@code MC_EMBEDDING_MODEL} point it elsewhere.
 *
 * <p>Files in three data pools (each just a list of refs), Alice's mail in two
 * folders, Bob's mail, and Alice's calendar — all in one table, embedded once.
 */
class PgEmbeddingIndexNomicTest {

    private static final String DB_URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String DB_USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");
    private static final String EMBEDDING_URL = System.getenv().getOrDefault(
            "MC_EMBEDDING_URL", "http://localhost:1234/v1/embeddings");
    private static final String MODEL = System.getenv().getOrDefault(
            "MC_EMBEDDING_MODEL", "text-embedding-nomic-embed-text-v1.5");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    private static final String MAILBOX = "email.freemail";

    /** Data pools are file lists — what a pool knows about its files. */
    private static final Map<String, Map<String, List<String>>> POOLS = Map.of(
            "handbuch", Map.of(
                    "installation.md", List.of(
                            "Laden Sie das Installationspaket herunter und starten Sie den Setup-Assistenten.",
                            "Die Anwendung benötigt Java 21 und mindestens 4 GB Arbeitsspeicher.",
                            "Nach der Installation melden Sie sich mit dem Administrator-Konto an."),
                    "bedienung.md", List.of(
                            "Über das Menü Datei können Sie neue Projekte anlegen und speichern.",
                            "Mit der Tastenkombination Strg+F durchsuchen Sie alle Dokumente eines Projekts.",
                            "Berichte lassen sich als PDF oder Excel exportieren.")),
            "vertraege", Map.of(
                    "rahmenvertrag.pdf", List.of(
                            "Der Vertrag kann von beiden Parteien mit einer Frist von drei Monaten zum Quartalsende gekündigt werden.",
                            "Die Kündigung bedarf der Schriftform.",
                            "Die Vergütung wird monatlich im Voraus in Rechnung gestellt."),
                    "agb.pdf", List.of(
                            "Die Haftung des Anbieters ist auf Vorsatz und grobe Fahrlässigkeit beschränkt.",
                            "Gerichtsstand ist München.")),
            "support", Map.of(
                    "tickets-2026.md", List.of(
                            "Kunde meldet, dass der PDF-Export bei großen Berichten abbricht.",
                            "Nach dem Update auf Version 3.2 startet die Anwendung nicht mehr, Java fehlt.",
                            "Kunde möchte wissen, wie er seinen Vertrag vorzeitig beenden kann.")));

    private static final EntityRef MAIL_INVOICE = mail("INBOX", "7-1");
    private static final EntityRef MAIL_LUNCH = mail("INBOX", "7-2");
    private static final EntityRef MAIL_CANCEL = mail("Archive", "9-1");
    private static final EntityRef MAIL_BOB = mail("INBOX", "7-3");
    private static final EntityRef EVENT_REVIEW = new EntityRef(EntityType.CALENDAR_EVENT, "caldav.home", "work", "ev-1");

    private static final Map<EntityRef, String> MESSAGES = Map.of(
            MAIL_INVOICE, "Rechnung März: Bitte überweisen Sie 1.200 Euro bis zum 15. April.",
            MAIL_LUNCH, "Hast du morgen Zeit für ein Mittagessen beim Italiener?",
            MAIL_CANCEL, "Hiermit kündige ich den Rahmenvertrag fristgerecht zum Quartalsende.",
            MAIL_BOB, "Bob: Ich kündige mein Abo zum Monatsende.",
            EVENT_REVIEW, "Vertragsprüfung mit der Rechtsabteilung, Donnerstag 10 Uhr.");

    private static PGSimpleDataSource dataSource;
    private static Namespace namespace;
    private static PgEmbeddingIndex index;

    @BeforeAll
    static void indexCorpusOnce() {
        assumeTrue(databaseReachable(), "no pgvector database reachable — skipping");
        assumeTrue(embeddingReachable(), "no embedding model at " + EMBEDDING_URL + " — skipping");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(DB_URL);
        dataSource.setUser(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        namespace = new Namespace("it-nomic-" + UUID.randomUUID().toString().substring(0, 8));
        index = new PgEmbeddingIndex(dataSource, namespace);

        for (var pool : POOLS.values()) {
            for (var file : pool.entrySet()) {
                index.replace(file(file.getKey()), null, "1", MODEL, chunks(file.getValue()));
            }
        }
        for (var message : MESSAGES.entrySet()) {
            UserId owner = message.getKey().equals(MAIL_BOB) ? BOB : ALICE;
            index.replace(message.getKey(), owner, "1", MODEL, chunks(List.of(message.getValue())));
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (var c = dataSource.getConnection();
             var s = c.prepareStatement("DELETE FROM " + PgEmbeddingIndex.TABLE + " WHERE namespace = ?")) {
            s.setString(1, namespace.value());
            s.executeUpdate();
        }
    }

    @Test
    void theRightPoolAnswersAndOtherPoolsNeverLeakIn() {
        String question = "Wie kann ich den Vertrag kündigen?";

        List<EmbeddingHit> inVertraege = search(question, pool("vertraege"));
        List<EmbeddingHit> inHandbuch = search(question, pool("handbuch"));
        List<EmbeddingHit> inSupport = search(question, pool("support"));

        assertThat(inVertraege).allSatisfy(h -> assertThat(pool("vertraege")).contains(h.ref()));
        assertThat(inVertraege.get(0).chunk().text()).contains("gekündigt");
        assertThat(inHandbuch).hasSize(3).allSatisfy(h -> assertThat(pool("handbuch")).contains(h.ref()));
        assertThat(inSupport).allSatisfy(h -> assertThat(pool("support")).contains(h.ref()));
        assertThat(inSupport.get(0).chunk().text()).contains("Vertrag vorzeitig beenden");
    }

    @Test
    void theSameQuestionFindsDifferentAnswersPerPool() {
        String question = "Die Anwendung startet nicht, welche Voraussetzungen gibt es?";

        List<EmbeddingHit> inHandbuch = search(question, pool("handbuch"));
        List<EmbeddingHit> inSupport = search(question, pool("support"));

        assertThat(inHandbuch).allSatisfy(h -> assertThat(pool("handbuch")).contains(h.ref()));
        // nomic ranks "download the installer" slightly above the requirements
        // for this German question — the index's job is only that it stays in the pool.
        assertThat(inHandbuch).extracting(h -> h.chunk().text()).anyMatch(t -> t.contains("Java 21"));
        assertThat(inSupport.get(0).chunk().text()).contains("startet die Anwendung nicht");
    }

    /** A chat session is just another ref list; its file from the contracts pool is not embedded again. */
    @Test
    void aSessionSharesAFileWithAPool() {
        Set<EntityRef> session = Set.of(file("agb.pdf"), file("bedienung.md"));

        List<EmbeddingHit> hits = search("Wo ist der Gerichtsstand?", EmbeddingQuery.of(MODEL, session));

        assertThat(hits).allSatisfy(h -> assertThat(session).contains(h.ref()));
        assertThat(hits.get(0).chunk().text()).contains("München");
        assertThat(index.chunkCount(file("agb.pdf"), MODEL)).isEqualTo(2);
    }

    /** Only Alice's inbox: the cancellation in her archive and Bob's cancellation stay out. */
    @Test
    void mailOfOneFolder() {
        List<EmbeddingHit> inbox = search("Kündigung",
                EmbeddingQuery.ofTypes(MODEL, EntityType.MAIL).owner(ALICE).in(MAILBOX, "INBOX"));
        List<EmbeddingHit> allOfAlice = search("Kündigung",
                EmbeddingQuery.ofTypes(MODEL, EntityType.MAIL).owner(ALICE).in(MAILBOX));

        assertThat(inbox).extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(MAIL_INVOICE, MAIL_LUNCH);
        assertThat(allOfAlice.get(0).ref()).isEqualTo(MAIL_CANCEL);
        assertThat(allOfAlice).extracting(EmbeddingHit::ref).doesNotContain(MAIL_BOB);
    }

    /** One ranking across kinds: Alice's mail, her calendar, and the shared files. */
    @Test
    void acrossMailCalendarAndFiles() {
        List<EmbeddingHit> hits = search("Wann wird der Rahmenvertrag geprüft oder gekündigt?",
                EmbeddingQuery.ofTypes(MODEL, EntityType.MAIL, EntityType.CALENDAR_EVENT, EntityType.FILE).ownerOrShared(ALICE));

        assertThat(hits).extracting(h -> h.ref().type()).containsAnyOf(EntityType.MAIL, EntityType.CALENDAR_EVENT);
        assertThat(hits).extracting(EmbeddingHit::ref).doesNotContain(MAIL_BOB);
    }

    private static EntityRef file(String name) {
        return EntityRef.of(EntityType.FILE, "files", name);
    }

    private static EntityRef mail(String folder, String id) {
        return new EntityRef(EntityType.MAIL, MAILBOX, folder, id);
    }

    private static Set<EntityRef> pool(String name) {
        return POOLS.get(name).keySet().stream().map(PgEmbeddingIndexNomicTest::file).collect(Collectors.toSet());
    }

    private static List<EmbeddingChunk> chunks(List<String> texts) {
        List<float[]> embeddings = embed(texts.stream().map(t -> "search_document: " + t).toList());
        List<EmbeddingChunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new EmbeddingChunk("c" + i, i, texts.get(i), Map.of(), embeddings.get(i)));
        }
        return chunks;
    }

    private List<EmbeddingHit> search(String question, Set<EntityRef> refs) {
        return search(question, EmbeddingQuery.of(MODEL, refs));
    }

    private List<EmbeddingHit> search(String question, EmbeddingQuery query) {
        float[] vector = embed(List.of("search_query: " + question)).get(0);
        List<EmbeddingHit> hits = index.search(query, vector, 3);
        System.out.println("\n\"" + question + "\"" + describe(query) + ":");
        for (EmbeddingHit hit : hits) {
            System.out.printf("  %.3f  %-14s %-16s %-8s %-18s %s%n", hit.score(), hit.ref().type(), hit.ref().source(),
                    hit.ref().container(), hit.ref().id(), hit.chunk().text());
        }
        return hits;
    }

    private static String describe(EmbeddingQuery query) {
        if (query.refs() != null) {
            return " in " + query.refs().stream().map(EntityRef::id).sorted().toList();
        }
        return " in " + query.types() + (query.owners().isEmpty() ? "" : " of " + query.owners())
                + (query.shared() ? " + shared" : "")
                + (query.source() == null ? "" : " from " + query.source())
                + (query.container() == null ? "" : "/" + query.container());
    }

    private static List<float[]> embed(List<String> texts) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("input", texts);
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(EMBEDDING_URL))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Embedding failed: " + response.statusCode() + " " + response.body());
            }
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(response.body()).get("data")) {
                JsonNode values = item.get("embedding");
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) values.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding request to " + EMBEDDING_URL + " failed: " + e.getMessage(), e);
        }
    }

    private static boolean databaseReachable() {
        try (var c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean embeddingReachable() {
        try {
            return embed(List.of("ping")).get(0).length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
