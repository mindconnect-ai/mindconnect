package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileUserMemoryRepositoryTest {

    private static final UserId ALICE = UserId.of("alice@example.com");
    private static final Namespace NS = new Namespace("acme");

    @TempDir Path dir;

    @Test
    void anEntryIsAMarkdownFileWithFrontMatterUnderTheNamespaceAndUser() throws Exception {
        FileUserMemoryRepository repo = new FileUserMemoryRepository(dir, NS);
        Instant created = Instant.parse("2026-09-01T08:00:00Z");
        MemoryEntry entry = new MemoryEntry(ALICE, "preferred-language", MemoryType.FEEDBACK,
                "Answers in German", "Always answer in German.\n\nWhy: she asked to.",
                SessionId.of("s1"), created, Instant.parse("2026-09-23T10:00:00Z"));

        repo.save(entry);

        Path file = dir.resolve("acme/memory/alice%40example.com/preferred-language.md");
        assertThat(Files.readString(file)).startsWith("---\nname: preferred-language\n")
                .contains("type: feedback\n", "source: s1\n")
                .endsWith("---\n\nAlways answer in German.\n\nWhy: she asked to.\n");
        assertThat(repo.find(ALICE, null, "preferred-language")).contains(entry);
        assertThat(repo.findByUser(ALICE)).containsExactly(entry);
    }

    @Test
    void usersAndNamespacesAreKeptApart() {
        new FileUserMemoryRepository(dir, NS).save(entry(ALICE, "role"));

        assertThat(new FileUserMemoryRepository(dir, NS).findByUser(UserId.of("bob"))).isEmpty();
        assertThat(new FileUserMemoryRepository(dir, new Namespace("other")).findByUser(ALICE)).isEmpty();
    }

    @Test
    void deletingRemovesTheFile() {
        FileUserMemoryRepository repo = new FileUserMemoryRepository(dir, NS);
        repo.save(entry(ALICE, "role"));

        assertThat(repo.delete(ALICE, null, "role")).isTrue();
        assertThat(repo.delete(ALICE, null, "role")).isFalse();
        assertThat(repo.findByUser(ALICE)).isEmpty();
    }

    @Test
    void aFileAsClaudeCodeWritesItIsReadToo() throws Exception {
        Path userDir = dir.resolve("acme/memory/alice%40example.com");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("deploy-target.md"), """
                ---
                name: deploy-target
                description: Deploys go to the Hetzner box
                metadata:
                  type: reference
                ---

                app.mindconnect.ai, via deploy-admin-ui.sh.
                """);

        assertThat(new FileUserMemoryRepository(dir, NS).find(ALICE, null, "deploy-target")).get().satisfies(e -> {
            assertThat(e.type()).isEqualTo(MemoryType.REFERENCE);
            assertThat(e.description()).isEqualTo("Deploys go to the Hetzner box");
            assertThat(e.content()).isEqualTo("app.mindconnect.ai, via deploy-admin-ui.sh.");
        });
    }

    @Test
    void aUserIdOfDotsDoesNotLeaveTheMemoryDirectory() {
        new FileUserMemoryRepository(dir, NS).save(entry(UserId.of(".."), "role"));

        assertThat(dir.resolve("acme/memory/%2E%2E/role.md")).exists();
    }

    @Test
    void anAgentsEntriesLieInADirectoryOfTheirOwnAndComeBackWithTheirAgent() {
        FileUserMemoryRepository repo = new FileUserMemoryRepository(dir, NS);
        AgentId secretary = AgentId.of("00000002-0000-0000-0000-000000000099");
        repo.save(entry(ALICE, "role"));
        repo.save(new MemoryEntry(ALICE, secretary, "role", MemoryType.PROJECT, "Travel desk", "c", null, null, null));

        assertThat(dir.resolve("acme/memory/alice%40example.com/agents/" + secretary.value() + "/role.md")).exists();
        assertThat(repo.find(ALICE, secretary, "role")).get().extracting(MemoryEntry::agentId).isEqualTo(secretary);
        assertThat(repo.find(ALICE, null, "role")).get().extracting(MemoryEntry::agentId).isNull();
        assertThat(repo.findByUser(ALICE)).hasSize(2);

        assertThat(repo.delete(ALICE, secretary, "role")).isTrue();
        assertThat(repo.find(ALICE, null, "role")).isPresent();
    }

    @Test
    void findAllReadsEveryUsersDirectoryBackToTheirId() {
        FileUserMemoryRepository repo = new FileUserMemoryRepository(dir, NS);
        repo.save(entry(ALICE, "a"));
        repo.save(entry(UserId.of("bob"), "b"));
        repo.save(entry(UserId.of(".."), "c"));

        assertThat(repo.findAll()).extracting(e -> e.userId().value())
                .containsExactlyInAnyOrder("alice@example.com", "bob", "..");
    }

    private static MemoryEntry entry(UserId user, String name) {
        return new MemoryEntry(user, name, MemoryType.USER, "d", "c", null, null, null);
    }
}
