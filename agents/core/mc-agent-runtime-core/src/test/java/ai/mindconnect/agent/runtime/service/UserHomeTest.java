package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A user's directory: created on first use, one per user, a session's own directory under it. */
class UserHomeTest {

    private static final UserId ALICE = UserId.of("alice");

    @TempDir
    Path tmp;

    @Test
    void aHomeIsCreatedOnFirstUse_andSessionsGetADirectoryUnderIt() throws Exception {
        var home = UserHome.under(tmp);
        assertThat(home.isConfigured()).isTrue();
        assertThat(home.template()).isEqualTo(tmp.resolve("home/{user}").toString());

        SessionId session = SessionId.random();
        Path alice = home.homeOf(ALICE).orElseThrow();
        assertThat(alice).isEqualTo(tmp.toRealPath().resolve("home/alice"));
        assertThat(Files.isDirectory(alice)).isTrue();

        assertThat(home.existingSessionDirOf(ALICE, session)).as("not there until asked for").isEmpty();
        Path own = home.sessionDirOf(ALICE, session).orElseThrow();
        assertThat(own).isEqualTo(alice.resolve("sessions").resolve(session.value()));
        assertThat(Files.isDirectory(own)).isTrue();
        assertThat(home.existingSessionDirOf(ALICE, session)).contains(own);
        assertThat(home.uploadsDirOf(ALICE, session)).contains(own.resolve("uploads"));
        assertThat(Files.isDirectory(own.resolve("uploads"))).isTrue();

        assertThat(home.homeOf(UserId.of("bob")).orElseThrow()).isEqualTo(tmp.toRealPath().resolve("home/bob"));
        assertThat(home.homeOf(UserId.of("../bob")).orElseThrow()).as("an id is one path segment")
                .isEqualTo(tmp.toRealPath().resolve("home/_._bob"));
    }

    @Test
    void noneAnswersEmptyEverywhere_andATemplateNeedsTheUser() {
        var none = UserHome.none();
        assertThat(none.isConfigured()).isFalse();
        assertThat(none.template()).isNull();
        assertThat(none.homeOf(ALICE)).isEmpty();
        assertThat(none.sessionDirOf(ALICE, SessionId.random())).isEmpty();
        assertThat(none.uploadsDirOf(ALICE, SessionId.random())).isEmpty();
        assertThat(UserHome.of(" ").isConfigured()).isFalse();
        assertThat(UserHome.under(null).isConfigured()).isFalse();

        assertThatThrownBy(() -> UserHome.of(tmp.resolve("everyone").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{user}");
        assertThat(UserHome.under(tmp).homeOf(null)).isEmpty();
        assertThat(UserHome.under(tmp).sessionDirOf(ALICE, null)).isEmpty();
    }

    @Test
    void theHomeIsAlsoTheDefaultRootAWorkingDirectoryMustLieUnder() throws Exception {
        var home = UserHome.under(tmp);
        var policy = WorkingDirPolicy.within(home.template()).forUser(ALICE);
        Path project = Files.createDirectories(home.homeOf(ALICE).orElseThrow().resolve("project"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));

        assertThat(policy.validate(project.toString())).isEqualTo(project.toString());
        assertThatThrownBy(() -> policy.validate(elsewhere.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
    }
}
