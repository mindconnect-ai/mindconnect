package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.fileindex.FileVectorIndex.FileHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.stream.Collectors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared table with real text and a real embedding model: nomic-embed-text
 * served by LM Studio (OpenAI-compatible {@code /v1/embeddings}). Skipped when
 * the database or LM Studio does not answer; {@code MC_EMBEDDING_URL} and
 * {@code MC_EMBEDDING_MODEL} point it elsewhere.
 *
 * <p>Three data pools about different things, each just a list of files, and
 * a chat session whose file list shares one file with a pool. The query about
 * cancelling a contract belongs to the contracts pool; searched in the manual
 * pool it must still only return chunks of the manual's files.
 */
class PgFileVectorIndexNomicTest {

    private static final String DB_URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String DB_USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");
    private static final String EMBEDDING_URL = System.getenv().getOrDefault(
            "MC_EMBEDDING_URL", "http://localhost:1234/v1/embeddings");
    private static final String EMBEDDING_MODEL = System.getenv().getOrDefault(
            "MC_EMBEDDING_MODEL", "text-embedding-nomic-embed-text-v1.5");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static final String MODEL = EMBEDDING_MODEL;

    /** Pools are file lists — what a data pool knows about its files. */
    private static final String HANDBUCH = "handbuch";
    private static final String VERTRAEGE = "vertraege";
    private static final String SUPPORT = "support";
    private static final Map<String, Map<String, List<String>>> CORPUS = Map.of(
            HANDBUCH, Map.of(
                    "installation.md", List.of(
                            "Laden Sie das Installationspaket herunter und starten Sie den Setup-Assistenten.",
                            "Die Anwendung benötigt Java 21 und mindestens 4 GB Arbeitsspeicher.",
                            "Nach der Installation melden Sie sich mit dem Administrator-Konto an."),
                    "bedienung.md", List.of(
                            "Über das Menü Datei können Sie neue Projekte anlegen und speichern.",
                            "Mit der Tastenkombination Strg+F durchsuchen Sie alle Dokumente eines Projekts.",
                            "Berichte lassen sich als PDF oder Excel exportieren.")),
            VERTRAEGE, Map.of(
                    "rahmenvertrag.pdf", List.of(
                            "Der Vertrag kann von beiden Parteien mit einer Frist von drei Monaten zum Quartalsende gekündigt werden.",
                            "Die Kündigung bedarf der Schriftform.",
                            "Die Vergütung wird monatlich im Voraus in Rechnung gestellt."),
                    "agb.pdf", List.of(
                            "Die Haftung des Anbieters ist auf Vorsatz und grobe Fahrlässigkeit beschränkt.",
                            "Gerichtsstand ist München.")),
            SUPPORT, Map.of(
                    "tickets-2026.md", List.of(
                            "Kunde meldet, dass der PDF-Export bei großen Berichten abbricht.",
                            "Nach dem Update auf Version 3.2 startet die Anwendung nicht mehr, Java fehlt.",
                            "Kunde möchte wissen, wie er seinen Vertrag vorzeitig beenden kann.")));

    private PGSimpleDataSource dataSource;
    private Namespace namespace;
    private PgFileVectorIndex index;

    @BeforeEach
    void setUp() {
        assumeTrue(databaseReachable(), "no pgvector database reachable — skipping");
        assumeTrue(embeddingReachable(), "no embedding model at " + EMBEDDING_URL + " — skipping");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(DB_URL);
        dataSource.setUser(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        namespace = new Namespace("it-nomic-" + UUID.randomUUID().toString().substring(0, 8));
        index = new PgFileVectorIndex(dataSource, namespace);

        for (var pool : CORPUS.values()) {
            for (var file : pool.entrySet()) {
                List<String> texts = file.getValue();
                List<float[]> embeddings = embed(texts.stream().map(t -> "search_document: " + t).toList());
                List<VectorChunk> chunks = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    chunks.add(new VectorChunk("c" + i, file.getKey(), i, texts.get(i), Map.of(), embeddings.get(i)));
                }
                index.replaceFile(FileId.of(file.getKey()), MODEL, chunks);
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (var c = dataSource.getConnection();
             var s = c.prepareStatement("DELETE FROM " + PgFileVectorIndex.TABLE + " WHERE namespace = ?")) {
            s.setString(1, namespace.value());
            s.executeUpdate();
        }
    }

    @Test
    void theRightPoolAnswersAndOtherPoolsNeverLeakIn() {
        String question = "Wie kann ich den Vertrag kündigen?";

        List<FileHit> inVertraege = search(question, VERTRAEGE);
        List<FileHit> inHandbuch = search(question, HANDBUCH);
        List<FileHit> inSupport = search(question, SUPPORT);
        List<FileHit> inAll = search(question, HANDBUCH, VERTRAEGE, SUPPORT);

        assertThat(inVertraege).allSatisfy(h -> assertThat(filesOf(VERTRAEGE)).contains(h.file()));
        assertThat(inVertraege.get(0).chunk().text()).contains("gekündigt");
        assertThat(inHandbuch).hasSize(3).allSatisfy(h -> assertThat(filesOf(HANDBUCH)).contains(h.file()));
        assertThat(inSupport).allSatisfy(h -> assertThat(filesOf(SUPPORT)).contains(h.file()));
        assertThat(inSupport.get(0).chunk().text()).contains("Vertrag vorzeitig beenden");
        assertThat(inAll.get(0).file()).isIn(FileId.of("rahmenvertrag.pdf"), FileId.of("tickets-2026.md"));
    }

    @Test
    void theSameQuestionFindsDifferentAnswersPerPool() {
        String question = "Die Anwendung startet nicht, welche Voraussetzungen gibt es?";

        List<FileHit> inHandbuch = search(question, HANDBUCH);
        List<FileHit> inSupport = search(question, SUPPORT);

        assertThat(inHandbuch).allSatisfy(h -> assertThat(filesOf(HANDBUCH)).contains(h.file()));
        // nomic ranks "download the installer" slightly above the requirements
        // for this German question — the index's job is only that it stays in the pool.
        assertThat(inHandbuch).extracting(h -> h.chunk().text()).anyMatch(t -> t.contains("Java 21"));
        assertThat(inSupport).allSatisfy(h -> assertThat(filesOf(SUPPORT)).contains(h.file()));
        assertThat(inSupport.get(0).chunk().text()).contains("startet die Anwendung nicht");
    }

    /** A chat session is just another file list; its file from the contracts pool is not embedded again. */
    @Test
    void aSessionSharesAFileWithAPool() {
        Set<FileId> session = Set.of(FileId.of("agb.pdf"), FileId.of("bedienung.md"));

        List<FileHit> hits = search("Wo ist der Gerichtsstand?", session);

        assertThat(hits).allSatisfy(h -> assertThat(session).contains(h.file()));
        assertThat(hits.get(0).chunk().text()).contains("München");
        assertThat(index.chunkCount(FileId.of("agb.pdf"), MODEL)).isEqualTo(2);
    }

    private static Set<FileId> filesOf(String... pools) {
        return java.util.Arrays.stream(pools)
                .flatMap(pool -> CORPUS.get(pool).keySet().stream())
                .map(FileId::of)
                .collect(Collectors.toSet());
    }

    private List<FileHit> search(String question, String... pools) {
        return search(question, filesOf(pools));
    }

    private List<FileHit> search(String question, Set<FileId> files) {
        float[] query = embed(List.of("search_query: " + question)).get(0);
        List<FileHit> hits = index.search(files, MODEL, query, 3);
        System.out.println("\n\"" + question + "\" in " + files.stream().map(FileId::value).sorted().toList() + ":");
        for (FileHit hit : hits) {
            System.out.printf("  %.3f  %-18s %s%n", hit.score(), hit.file(), hit.chunk().text());
        }
        return hits;
    }

    private static List<float[]> embed(List<String> texts) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", EMBEDDING_MODEL);
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
