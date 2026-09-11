package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The working directory on a session: set, carried by every copy, in the
 * JSON — and absent from every session written before it existed.
 */
class AgentSessionWorkingDirTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static AgentSession session() {
        return AgentSession.start(AgentId.random(), UserId.of("u"), ConversationId.random());
    }

    @Test
    void aFreshSessionHasNone_andTakesOne() {
        AgentSession fresh = session();
        assertThat(fresh.hasWorkingDir()).isFalse();
        assertThat(fresh.workingDir()).isNull();

        AgentSession working = fresh.withWorkingDir("/home/me/src/app");
        assertThat(working.hasWorkingDir()).isTrue();
        assertThat(working.workingDir()).isEqualTo("/home/me/src/app");
        assertThat(working.withWorkingDir(null).hasWorkingDir()).as("cleared").isFalse();
        assertThat(working.withWorkingDir("  ").hasWorkingDir()).as("blank is none").isFalse();
    }

    @Test
    void everyCopyCarriesIt() {
        AgentSession s = session().withWorkingDir("/work");

        assertThat(s.withTitle("t").workingDir()).isEqualTo("/work");
        assertThat(s.withApprovedTool("bash").workingDir()).isEqualTo("/work");
        assertThat(s.withActivatedTools(java.util.List.of("glob")).workingDir()).isEqualTo("/work");
        assertThat(s.withAttachedFiles(java.util.List.of(AttachedFile.named("a.md"))).workingDir()).isEqualTo("/work");
        assertThat(s.withoutAttachedFile("a.md").workingDir()).isEqualTo("/work");
        assertThat(s.withSessionAgents(java.util.List.of()).workingDir()).isEqualTo("/work");
        assertThat(s.complete().workingDir()).isEqualTo("/work");
        assertThat(s.error().workingDir()).isEqualTo("/work");
    }

    @Test
    void jsonCarriesIt_andASessionWrittenBeforeReadsAsNone() throws Exception {
        AgentSession s = session().withWorkingDir("/work");
        String json = JSON.writeValueAsString(s);
        assertThat(json).contains("\"workingDir\":\"/work\"");
        assertThat(JSON.readValue(json, AgentSession.class)).isEqualTo(s);

        String legacy = json.replace(",\"workingDir\":\"/work\"", "");
        assertThat(legacy).doesNotContain("workingDir");
        AgentSession read = JSON.readValue(legacy, AgentSession.class);
        assertThat(read.hasWorkingDir()).isFalse();
        assertThat(read.id()).isEqualTo(s.id());
    }

    @Test
    void additionalDirectoriesRideAlong_andSurviveJson() throws Exception {
        AgentSession s = session().withWorkingDir("/work").withAdditionalDirs(java.util.List.of("/lib", "/data"));

        assertThat(s.additionalDirs()).containsExactly("/lib", "/data");
        assertThat(s.withWorkingDir("/elsewhere").additionalDirs()).containsExactly("/lib", "/data");
        assertThat(s.withTitle("t").additionalDirs()).containsExactly("/lib", "/data");
        assertThat(s.withAdditionalDirs(null).additionalDirs()).isEmpty();

        String json = JSON.writeValueAsString(s);
        assertThat(json).contains("\"additionalDirs\":[\"/lib\",\"/data\"]");
        assertThat(JSON.readValue(json, AgentSession.class)).isEqualTo(s);
        AgentSession legacy = JSON.readValue(json.replace(",\"additionalDirs\":[\"/lib\",\"/data\"]", ""),
                AgentSession.class);
        assertThat(legacy.additionalDirs()).isEmpty();
    }

    @Test
    void oneMoreDirectoryIsAddedOnce_andNotWhenTheSessionReachesItAlready() {
        AgentSession s = session().withWorkingDir("/work").withAdditionalDirs(java.util.List.of("/lib"));

        AgentSession added = s.withAdditionalDir("/home/u/sessions/1");
        assertThat(added.additionalDirs()).containsExactly("/lib", "/home/u/sessions/1");
        assertThat(added.withAdditionalDir("/home/u/sessions/1").additionalDirs())
                .as("no duplicates").containsExactly("/lib", "/home/u/sessions/1");
        assertThat(s.withAdditionalDir("/work")).as("the working directory itself").isSameAs(s);
        assertThat(s.withAdditionalDir("/work/sessions/1")).as("inside the working directory").isSameAs(s);
        assertThat(s.withAdditionalDir("/lib/sub")).as("inside an additional directory").isSameAs(s);
        assertThat(s.withAdditionalDir("/workshop").additionalDirs())
                .as("a path prefix, not a string prefix").containsExactly("/lib", "/workshop");
        assertThat(s.withAdditionalDir(null)).isSameAs(s);
        assertThat(session().withAdditionalDir("/own").additionalDirs())
                .as("a session without a working directory").containsExactly("/own");
    }

    @Test
    void theHeaderViewIsUntouched() {
        // A header is a list row: the working directory is not part of it.
        AgentSession s = new AgentSession(SessionId.random(), AgentId.random(), UserId.of("u"),
                ConversationId.random(), "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null);
        ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader header = s;
        assertThat(header.title()).isEqualTo("t");
    }
}
