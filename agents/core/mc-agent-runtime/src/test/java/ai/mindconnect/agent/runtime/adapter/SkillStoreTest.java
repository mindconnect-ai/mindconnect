package ai.mindconnect.agent.runtime.adapter;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.file.FileSkillRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import ai.mindconnect.common.StaleVersionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A stored skill survives the round trip through JSON, is found by name, and
 * two edits of the same one cannot both land. File and in-memory stores
 * behave the same.
 */
class SkillStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void theFileStoreKeepsEveryFieldAndChecksVersions(@TempDir Path dir) {
        storeBehaves(new FileSkillRepository(dir, MAPPER, new Namespace("test")));
    }

    @Test
    void theInMemoryStoreBehavesTheSame() {
        storeBehaves(new InMemorySkillRepository());
    }

    static void storeBehaves(SkillRepository repo) {
        Skill saved = repo.save(Skill.create("weekly-report", "Use when writing the weekly report",
                "1. Read last week's.\n2. Write this week's.", List.of("file_read")));

        assertThat(saved.version()).isEqualTo(1L);

        Skill read = repo.findById(saved.id()).orElseThrow();
        assertThat(read.name()).isEqualTo("weekly-report");
        assertThat(read.description()).isEqualTo("Use when writing the weekly report");
        assertThat(read.instructions()).isEqualTo("1. Read last week's.\n2. Write this week's.");
        assertThat(read.tools()).containsExactly("file_read");
        assertThat(read.enabled()).isTrue();
        assertThat(read.source()).isEqualTo(SkillSource.MANAGED);
        assertThat(read.createdAt()).isNotNull();

        assertThat(repo.findByName("Weekly-Report")).as("names are matched case-insensitively")
                .contains(read);
        assertThat(repo.findAll()).containsExactly(read);

        Skill edited = repo.save(read.withFields(read.name(), read.description(),
                "3. Send it.", read.tools(), false));
        assertThat(edited.version()).isEqualTo(2L);
        assertThat(repo.findById(saved.id()).orElseThrow().enabled()).isFalse();

        assertThatThrownBy(() -> repo.save(read.withFields(read.name(), "Someone else's edit",
                read.instructions(), read.tools(), true)))
                .as("the second save, made against the version both opened, is refused")
                .isInstanceOf(StaleVersionException.class);

        repo.deleteById(saved.id());
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void aNameNoModelCouldTypeIsNeverStored() {
        SkillRepository repo = new InMemorySkillRepository();

        assertThatThrownBy(() -> repo.save(Skill.create("Weekly Report", "d", "i", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.findAll()).isEmpty();
    }
}
