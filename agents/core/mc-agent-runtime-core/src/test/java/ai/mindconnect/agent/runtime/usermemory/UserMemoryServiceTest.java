package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMemoryServiceTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final UserMemoryService service = new UserMemoryService(new MapUserMemoryRepository());

    @Test
    void writingUnderAnExistingNameReplacesTheEntryAndKeepsWhenItWasFirstWritten() {
        UserMemoryService.Written first = service.write(ALICE, "Preferred Language", MemoryType.FEEDBACK,
                "Answers in German", "Always answer in German.", SessionId.of("s1"));
        UserMemoryService.Written second = service.write(ALICE, "preferred_language", MemoryType.FEEDBACK,
                "Answers in English now", "Switched to English.", SessionId.of("s2"));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(service.list(ALICE)).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("preferred-language");
            assertThat(e.description()).isEqualTo("Answers in English now");
            assertThat(e.sourceSessionId()).isEqualTo(SessionId.of("s2"));
            assertThat(e.createdAt()).isEqualTo(first.entry().createdAt());
        });
    }

    @Test
    void everyUserHasAMemoryOfTheirOwn() {
        service.write(ALICE, "role", MemoryType.USER, "Buyer", "Head of purchasing.", null);

        assertThat(service.list(BOB)).isEmpty();
        assertThat(service.read(BOB, "role")).isEmpty();
        assertThat(service.delete(BOB, "role")).isFalse();
        assertThat(service.read(ALICE, "role")).isPresent();
    }

    @Test
    void theListStartsWithTheMostRecentlyChangedEntry() throws InterruptedException {
        service.write(ALICE, "old", MemoryType.USER, "old", "old", null);
        Thread.sleep(5);
        service.write(ALICE, "new", MemoryType.USER, "new", "new", null);

        assertThat(service.list(ALICE)).extracting(MemoryEntry::name).containsExactly("new", "old");
    }

    @Test
    void theDescriptionIsFoldedIntoOneLine() {
        service.write(ALICE, "x", MemoryType.USER, "  two\n  lines ", "c", null);

        assertThat(service.read(ALICE, "x")).get().extracting(MemoryEntry::description).isEqualTo("two lines");
    }

    @Test
    void namesAreSafeAsFileNames() {
        assertThat(UserMemoryService.normaliseName("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(UserMemoryService.normaliseName("Größe der Firma")).isEqualTo("groesse-der-firma");
        assertThatThrownBy(() -> UserMemoryService.normaliseName("  ..  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntryOutOfBoundsIsRefusedWithAReasonTheModelCanActOn() {
        assertThatThrownBy(() -> service.write(ALICE, "x", MemoryType.USER, "d".repeat(201), "c", null))
                .hasMessageContaining("'description'");
        assertThatThrownBy(() -> service.write(ALICE, "x", MemoryType.USER, "d", "c".repeat(4001), null))
                .hasMessageContaining("'content'");
    }

    @Test
    void withoutContentTheDescriptionIsTheMemory() {
        service.write(ALICE, "colour", MemoryType.USER, "Favourite colour is teal", " ", null);

        assertThat(service.read(ALICE, "colour")).get().extracting(MemoryEntry::content)
                .isEqualTo("Favourite colour is teal");
    }

    /**
     * A model may call memory_delete and memory_write on the same name in one
     * round, and the tools run in parallel. Whatever order they land in, what
     * the write reports must match what is stored: an entry that survives was
     * written after the delete, so the write created it.
     */
    @Test
    void aParallelWriteAndDeleteOfOneEntryLeaveAStateTheirResultsDescribe() throws Exception {
        UserMemoryService slow = new UserMemoryService(new MapUserMemoryRepository() {
            @Override public MemoryEntry save(MemoryEntry entry) {
                pause();
                return super.save(entry);
            }
            @Override public boolean delete(UserId userId, ai.mindconnect.agent.AgentId agentId, String name) {
                pause();
                return super.delete(userId, agentId, name);
            }
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                slow.write(ALICE, "prefs", MemoryType.USER, "old", "old", null);
                var write = pool.submit(() -> slow.write(ALICE, "prefs", MemoryType.USER, "new", "new", null));
                var delete = pool.submit(() -> slow.delete(ALICE, "prefs"));
                boolean created = write.get().created();
                delete.get();
                boolean exists = slow.read(ALICE, "prefs").isPresent();

                assertThat(exists && !created)
                        .as("round %d: the write said 'updated' but its entry outlived the delete", i)
                        .isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void anAgentsMemoryIsApartFromTheUsersOwnAndFromOtherAgents() {
        var secretary = ai.mindconnect.agent.AgentId.of("secretary");
        var coder = ai.mindconnect.agent.AgentId.of("coder");
        service.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "c", null);
        service.write(ALICE, secretary, "role", MemoryType.PROJECT, "Books her travel via Egencia", "c", null);
        service.write(ALICE, coder, "stack", MemoryType.PROJECT, "Java 21", "c", null);

        assertThat(service.read(ALICE, "role")).get().extracting(MemoryEntry::description).isEqualTo("Head of purchasing");
        assertThat(service.read(ALICE, secretary, "role")).get().extracting(MemoryEntry::description)
                .isEqualTo("Books her travel via Egencia");
        assertThat(service.list(ALICE, secretary, MemoryReach.USER)).extracting(MemoryEntry::description)
                .containsExactly("Head of purchasing");
        assertThat(service.list(ALICE, secretary, MemoryReach.AGENT)).extracting(MemoryEntry::description)
                .containsExactly("Books her travel via Egencia");
        assertThat(service.list(ALICE, secretary, MemoryReach.BOTH)).extracting(MemoryEntry::name)
                .containsExactlyInAnyOrder("role", "role").hasSize(2);
        assertThat(service.list(ALICE)).hasSize(3);

        assertThat(service.delete(ALICE, secretary, "role")).isTrue();
        assertThat(service.read(ALICE, "role")).as("the user's own entry of the same name stays").isPresent();
    }

    @Test
    void anAdminSeesEveryUsersEntriesByUser() {
        service.write(BOB, "b", MemoryType.USER, "d", "c", null);
        service.write(ALICE, "a", MemoryType.USER, "d", "c", null);
        service.write(ALICE, ai.mindconnect.agent.AgentId.of("x"), "a", MemoryType.USER, "d", "c", null);

        assertThat(service.listAll()).extracting(e -> e.userId().value()).containsExactly("alice", "alice", "bob");
    }

    @Test
    void aFullMemoryTakesUpdatesButNoNewEntries() {
        for (int i = 0; i < UserMemoryService.MAX_ENTRIES; i++) {
            service.write(ALICE, "m" + i, MemoryType.USER, "d", "c", null);
        }

        assertThat(service.write(ALICE, "m0", MemoryType.USER, "d2", "c2", null).created()).isFalse();
        assertThatThrownBy(() -> service.write(ALICE, "one-more", MemoryType.USER, "d", "c", null))
                .hasMessageContaining("delete an outdated one");
    }
}
