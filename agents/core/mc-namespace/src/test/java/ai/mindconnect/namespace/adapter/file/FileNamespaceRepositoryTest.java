package ai.mindconnect.namespace.adapter.file;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
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

    private static final Email DAVID = Email.of("david@local");
    private static final Email ALICE = Email.of("alice@local");

    private static NamespaceDefinition acme(Email... users) {
        return new NamespaceDefinition(new Namespace("acme"), "ACME", DAVID,
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(), Set.of(users));
    }

    private static Actor who(String name) {
        return Actor.of(UserId.of(name), Email.of(name + "@local"));
    }

    @Test
    void aNamespaceSurvivesTheRoundTripUnderTheSystemDirectory() throws Exception {
        var repo = new FileNamespaceRepository(dir, mapper);
        NamespaceDefinition acme = acme(ALICE);
        repo.save(acme);

        assertThat(repo.findById(new Namespace("acme"))).contains(acme);
        assertThat(repo.findAll()).containsExactly(acme);
        assertThat(Files.exists(dir.resolve("system/namespaces/acme.json"))).isTrue();
        assertThat(Files.readString(dir.resolve("system/namespaces/acme.json")))
                .contains("\"id\" : \"acme\"")                       // ids are plain values in JSON
                .contains("\"createdBy\" : \"david@local\"")     // and so are addresses
                .contains("\"users\" : [ \"alice@local\" ]");
    }

    @Test
    void theNamespacesVariablesSurviveTheRoundTrip() {
        var repo = new FileNamespaceRepository(dir, mapper);
        NamespaceDefinition acme = acme(ALICE).withEnvironment(java.util.Map.of("OPENAI_API_KEY", "enc:abc"));
        repo.save(acme);

        assertThat(repo.findById(new Namespace("acme"))).contains(acme);
        assertThat(repo.findById(new Namespace("acme"))).get().extracting(NamespaceDefinition::environment)
                .isEqualTo(java.util.Map.of("OPENAI_API_KEY", "enc:abc"));
    }

    @Test
    void insertRefusesAnIdThatIsTaken() {
        var repo = new FileNamespaceRepository(dir, mapper);

        assertThat(repo.insert(acme())).isTrue();
        assertThat(repo.insert(new NamespaceDefinition(new Namespace("acme"), "Other", Email.of("bob@local"),
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(), Set.of()))).isFalse();
        assertThat(repo.findById(new Namespace("acme"))).get().extracting(NamespaceDefinition::createdBy).isEqualTo(DAVID);
    }

    @Test
    void aRecordWrittenWhenTheFieldWasStillCalledOwnerAndMembersWasOneListStillReads() throws Exception {
        Files.createDirectories(dir.resolve("system/namespaces"));
        Files.writeString(dir.resolve("system/namespaces/old.json"), """
                {"id":"old","displayName":null,"owner":"david@local","createdAt":"2026-09-15T12:00:00Z",\
                "members":["david@local","alice@local"]}
                """);

        assertThat(new FileNamespaceRepository(dir, mapper).findById(new Namespace("old"))).get()
                .satisfies(ns -> assertThat(ns.createdBy()).isEqualTo(DAVID))
                .satisfies(ns -> assertThat(ns.admins()).as("the creator shapes it").containsExactly(DAVID))
                .satisfies(ns -> assertThat(ns.users()).as("everyone else worked in it").containsExactly(ALICE));
    }

    @Test
    void aRecordFrom082ListsUserIds_andTheyKeepTheirNamespaceAfterTheUpgrade() throws Exception {
        // What 0.8.2 wrote: the creator and the members by user id, not by address.
        Files.createDirectories(dir.resolve("system/namespaces"));
        Files.writeString(dir.resolve("system/namespaces/old.json"), """
                {"id":"old","displayName":null,"createdBy":"david","createdAt":"2026-09-15T12:00:00Z",\
                "members":["david","alice"]}
                """);
        var repo = new FileNamespaceRepository(dir, mapper);
        Actor david = Actor.of(UserId.of("david"), Email.of("david@corp.example"));
        Actor alice = Actor.of(UserId.of("Alice"), Email.of("alice@corp.example"));
        Actor mallory = Actor.of(UserId.of("mallory"), Email.of("david@evil.example"));

        NamespaceDefinition old = repo.findById(new Namespace("old")).orElseThrow();
        assertThat(old.role(david)).contains(NamespaceRole.ADMIN);
        assertThat(old.isCreator(david)).isTrue();
        assertThat(old.role(alice)).as("an id matches whatever case it signs in with").contains(NamespaceRole.USER);
        assertThat(old.role(mallory)).as("an old entry is an id, never the local part of an address").isEmpty();
        assertThat(repo.findFor(alice)).extracting(NamespaceDefinition::id).containsExactly(new Namespace("old"));
        assertThat(old.entryOf(alice)).contains(Email.of("alice"));
    }

    @Test
    void findForAnswersTheNMSide() {
        var repo = new FileNamespaceRepository(dir, mapper);
        repo.save(acme(ALICE));
        repo.save(new NamespaceDefinition(new Namespace("beta"), null, ALICE,
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(), Set.of()));

        assertThat(repo.findFor(who("alice"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"), new Namespace("beta"));
        assertThat(repo.findFor(who("david"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"));
        assertThat(repo.findFor(who("nobody"))).isEmpty();
    }

    @Test
    void saveReplacesAndDeleteRemoves() {
        var repo = new FileNamespaceRepository(dir, mapper);
        repo.save(acme());
        repo.save(acme(ALICE));

        assertThat(repo.findById(new Namespace("acme")).orElseThrow())
                .satisfies(ns -> assertThat(ns.admins()).containsExactly(DAVID))
                .satisfies(ns -> assertThat(ns.users()).containsExactly(ALICE));
        assertThat(repo.deleteById(new Namespace("acme"))).isTrue();
        assertThat(repo.deleteById(new Namespace("acme"))).isFalse();
        assertThat(repo.findAll()).isEmpty();
    }
}
