package ai.mindconnect.namespace.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamespaceRepositoryTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static NamespaceDefinition acme(UserId... members) {
        return new NamespaceDefinition(new Namespace("acme"), "ACME", UserId.of("david"),
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(members));
    }

    @Test
    void aNamespaceSurvivesTheRoundTripUnderTheSystemDirectory() throws Exception {
        var repo = new FileNamespaceRepository(dir, mapper);
        NamespaceDefinition acme = acme(UserId.of("alice"));
        repo.save(acme);

        assertThat(repo.findById(new Namespace("acme"))).contains(acme);
        assertThat(repo.findAll()).containsExactly(acme);
        assertThat(Files.exists(dir.resolve("system/namespaces/acme.json"))).isTrue();
        assertThat(Files.readString(dir.resolve("system/namespaces/acme.json")))
                .contains("\"id\" : \"acme\"")                       // ids are plain values in JSON
                .contains("\"owner\" : \"david\"");
    }

    @Test
    void findByMemberAnswersTheNMSide() {
        var repo = new FileNamespaceRepository(dir, mapper);
        repo.save(acme(UserId.of("alice")));
        repo.save(new NamespaceDefinition(new Namespace("beta"), null, UserId.of("alice"),
                Instant.parse("2026-09-15T12:00:00Z"), Set.of()));

        assertThat(repo.findByMember(UserId.of("alice"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"), new Namespace("beta"));
        assertThat(repo.findByMember(UserId.of("david"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"));
        assertThat(repo.findByMember(UserId.of("nobody"))).isEmpty();
    }

    @Test
    void saveReplacesAndDeleteRemoves() {
        var repo = new FileNamespaceRepository(dir, mapper);
        repo.save(acme());
        repo.save(acme(UserId.of("alice")));

        assertThat(repo.findById(new Namespace("acme")).orElseThrow().members())
                .containsExactlyInAnyOrder(UserId.of("david"), UserId.of("alice"));
        assertThat(repo.deleteById(new Namespace("acme"))).isTrue();
        assertThat(repo.deleteById(new Namespace("acme"))).isFalse();
        assertThat(repo.findAll()).isEmpty();
    }
}
