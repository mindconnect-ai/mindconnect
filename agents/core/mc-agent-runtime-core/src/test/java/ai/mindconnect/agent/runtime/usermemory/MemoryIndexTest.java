package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memory index stands in the prompt of an agent that has a memory tool —
 * read-write with {@code memory_write}, read-only with {@code memory_read}
 * alone — and nowhere else.
 */
class MemoryIndexTest {

    private static final UserId ALICE = UserId.of("alice");

    private final UserMemoryService service = new UserMemoryService(new MapUserMemoryRepository());
    private final MemoryIndex index = new MemoryIndex(service);

    @Test
    void anAgentWithoutMemoryToolsSeesNoSection() {
        service.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "c", null);

        assertThat(index.render(agent("todo_write"), session(ALICE))).isEmpty();
    }

    @Test
    void anAgentThatWritesReadsTheRulesAndOneLinePerEntry() {
        service.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "c", null);
        service.write(ALICE, "short-answers", MemoryType.FEEDBACK, "Wants short answers", "c", null);

        String section = index.render(agent("memory_write", "memory_read"), session(ALICE));

        assertThat(section).startsWith("\n\n## Memory\n")
                .contains("`memory_write`", "not instructions")
                .contains("- role (user): Head of purchasing")
                .contains("- short-answers (feedback): Wants short answers");
    }

    @Test
    void anAgentThatOnlyReadsIsToldTheMemoryIsReadOnly() {
        String section = index.render(agent("memory_read"), session(ALICE));

        assertThat(section).contains("read it but not change it").doesNotContain("`memory_write`")
                .endsWith("No memories saved yet — there is nothing to read.");
    }

    @Test
    void aDisabledToolDoesNotCount() {
        AgentTool disabled = new AgentTool(AgentTool.of("memory_write").id(), "memory_write", null,
                java.util.Map.of(), false, false, false, null);

        assertThat(index.render(agent(List.of(disabled)), session(ALICE))).isEmpty();
    }

    @Test
    void onlyTheUsersOwnEntriesAreListed() {
        service.write(UserId.of("bob"), "bobs-secret", MemoryType.USER, "Bob's", "c", null);

        assertThat(index.render(agent("memory_read"), session(ALICE))).doesNotContain("bobs-secret");
    }

    @Test
    void aLongMemoryIsCutAndPointsToTheFullList() {
        for (int i = 0; i < MemoryIndex.MAX_LINES + 3; i++) {
            service.write(ALICE, "m" + i, MemoryType.USER, "d" + i, "c", null);
        }

        String section = index.render(agent("memory_read"), session(ALICE));

        assertThat(section.lines().filter(l -> l.startsWith("- m")).count()).isEqualTo(MemoryIndex.MAX_LINES);
        assertThat(section).contains("… and 3 older ones");
    }

    @Test
    void anAgentWithItsOwnMemoryListsOnlyItsOwnEntries() {
        AgentDefinition secretary = agent(List.of(scoped("memory_write", "agent"), scoped("memory_read", "agent")));
        service.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "c", null);
        service.write(ALICE, secretary.id(), "travel", MemoryType.PROJECT, "Books via Egencia", "c", null);
        service.write(ALICE, AgentId.random(), "stack", MemoryType.PROJECT, "Java 21", "c", null);

        String section = index.render(secretary, session(ALICE));

        assertThat(section).contains("memory of your own", "- travel (project): Books via Egencia")
                .doesNotContain("Head of purchasing").doesNotContain("Java 21");
    }

    @Test
    void anAgentWithBothMemoriesSaysWhichEachEntryIsIn() {
        AgentDefinition secretary = agent(List.of(scoped("memory_write", "both")));
        service.write(ALICE, "role", MemoryType.USER, "Head of purchasing", "c", null);
        service.write(ALICE, secretary.id(), "travel", MemoryType.PROJECT, "Books via Egencia", "c", null);

        String section = index.render(secretary, session(ALICE));

        assertThat(section).contains("two persistent memories")
                .contains("- role (user, user's): Head of purchasing")
                .contains("- travel (project, yours): Books via Egencia");
    }

    private static AgentTool scoped(String tool, String scope) {
        return AgentTool.of(tool, null, java.util.Map.of(MemoryReach.OVERRIDE, scope));
    }

    private static AgentSession session(UserId user) {
        return AgentSession.start(AgentId.random(), user, ConversationId.random());
    }

    private static AgentDefinition agent(String... tools) {
        return agent(Stream.of(tools).map(AgentTool::of).toList());
    }

    private static AgentDefinition agent(List<AgentTool> tools) {
        return new AgentDefinition(AgentId.random(), "main", "d", null, null,
                "You are a helpful agent.", null, "gpt", 10, null,
                AgentDefinitionStatus.ACTIVE, tools, List.of(), List.of(), null, null,
                Instant.now(), Instant.now());
    }
}
