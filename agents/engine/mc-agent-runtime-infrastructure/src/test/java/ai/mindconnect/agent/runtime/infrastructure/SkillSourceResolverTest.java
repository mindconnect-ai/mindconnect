package ai.mindconnect.agent.runtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.skill.adapter.InMemorySkillRepository;
import ai.mindconnect.agent.skill.domain.Skill;
import ai.mindconnect.agent.skill.port.out.SkillRepository;

/**
 * An agent's skills come from the store, the user's files and the project's
 * files; the more specific place wins a name.
 */
class SkillSourceResolverTest {

    @TempDir
    Path dir;

    private final SkillRepository stored = new InMemorySkillRepository(ScopeSupplier.local());

    @Test
    void theProjectsSkillWinsOverTheUsers_andTheUsersOverTheStoredOne() throws Exception {
        stored.save(Skill.create("report", "Stored way.", "Write it the stored way.", List.of()));
        stored.save(Skill.create("stored-only", "Only stored.", "Do it.", List.of()));
        Path users = Files.createDirectories(dir.resolve("skills-of").resolve("ada"));
        Files.writeString(users.resolve("report.md"), "Write it the user's way.");
        Files.writeString(users.resolve("mine.md"), "Ada's own.");
        Path project = Files.createDirectories(dir.resolve("project"));
        Path projectSkills = Files.createDirectories(project.resolve(".mindconnect").resolve("skills"));
        Files.writeString(projectSkills.resolve("report.md"), "Write it the project's way.");

        SkillSourceResolver resolver = new SkillSourceResolver(() -> stored,
                UserSkillDirectory.of(dir.resolve("skills-of").resolve("{user}").toString()));
        Session session = new Session(SessionId.of("s1"), UserId.of("ada"), null, null,
                Map.of(ManagedSession.WORKING_DIRECTORY, project.toString()), Instant.EPOCH);

        assertThat(resolver.skills(session)).extracting(SkillSummary::name)
                .containsExactly("mine", "report", "stored-only");
        assertThat(resolver.resolve("report", session).orElseThrow().instructions())
                .contains("the project's way");
        // Somebody else, working nowhere: the stored skills only.
        Session other = new Session(SessionId.of("s2"), UserId.of("bob"), null, null, Map.of(), Instant.EPOCH);
        assertThat(resolver.skills(other)).extracting(SkillSummary::name).containsExactly("report", "stored-only");
        assertThat(resolver.resolve("report", other).orElseThrow().instructions()).contains("the stored way");
    }

    @Test
    void aSkillSwitchedOffIsNotOffered_andTheUsersFilesCanBeSwitchedOff() throws Exception {
        stored.save(Skill.create("hidden", "Off.", "Do it.", List.of())
                .withFields("hidden", "Off.", "Do it.", List.of(), false));
        Path users = Files.createDirectories(dir.resolve("skills"));
        Files.writeString(users.resolve("mine.md"), "Ada's own.");
        Session session = new Session(SessionId.of("s1"), UserId.of("ada"), null, null, Map.of(), Instant.EPOCH);

        assertThat(new SkillSourceResolver(() -> stored, UserSkillDirectory.of(users.toString())).skills(session))
                .extracting(SkillSummary::name).containsExactly("mine");
        assertThat(new SkillSourceResolver(() -> stored, UserSkillDirectory.of(UserSkillDirectory.OFF))
                .skills(session)).isEmpty();
    }
}
