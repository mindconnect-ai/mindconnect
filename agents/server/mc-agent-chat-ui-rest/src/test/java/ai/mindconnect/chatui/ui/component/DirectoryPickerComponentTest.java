package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.WorkingDirBrowser;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chooser: one path field, folders to step into, one button that takes
 * the field's path — each a URL on the chat controller, the field's value
 * travelling as the form.
 */
class DirectoryPickerComponentTest {

    private static final SessionId SESSION = SessionId.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String BASE = "/chat/api/sessions/" + SESSION + "/dirs";

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    private static AgentSession session() {
        return AgentSession.start(AgentId.random(), UserId.of("u"), ConversationId.random())
                .withWorkingDir("/srv/users/u/app")
                .withAdditionalDirs(List.of("/srv/users/u/lib"));
    }

    @Test
    void theFieldShowsTheListedDirectory_andUseOpenAndAddSubmitIt() throws Exception {
        var listing = new WorkingDirBrowser.Listing("/srv/users/u", "/srv/users/u/app", "/srv/users/u",
                List.of("/srv/users/u/app/src", "/srv/users/u/app/my docs"), false);

        String out = json(DirectoryPickerComponent.form(SESSION, session(), listing));

        assertThat(out).as("the field is not called path: a GET action in the form would carry it beside its own path parameter")
                .contains("\"id\":\"dir\"").doesNotContain("\"id\":\"path\"").contains("\"value\":\"/srv/users/u/app\"");
        assertThat(out).as("use, open and add post the form, so the field's path travels with them")
                .contains("\"url\":\"" + BASE + "/use\"")
                .contains("\"url\":\"" + BASE + "/go\"")
                .contains("\"url\":\"" + BASE + "/add\"")
                .contains("\"payload\":\"" + DirectoryPickerComponent.ID + "\"");
        assertThat(out).as("folders are steps, by their own name; up leads to the parent")
                .contains("\"label\":\"my docs\"")
                .contains("\"url\":\"" + BASE + "?path=/srv/users/u/app/my%20docs\"")
                .contains("\"label\":\"Up\"")
                .contains("\"url\":\"" + BASE + "?path=/srv/users/u\"");
        assertThat(out).contains("Folders in app").contains("Anything under /srv/users/u goes.")
                .as("the add button names the folder shown").contains("\"label\":\"Add app\"");
        assertThat(out).as("a new folder: named in its own field, created by a form action")
                .contains("\"id\":\"newFolder\"")
                .contains("\"url\":\"" + BASE + "/create\"")
                .contains("Name of a folder to create in app");
    }

    @Test
    void additionalDirectoriesAreListedWithARemoveEach() throws Exception {
        var listing = new WorkingDirBrowser.Listing("/srv/users/u", "/srv/users/u", null, List.of(), false);

        String out = json(DirectoryPickerComponent.form(SESSION, session(), listing));

        assertThat(out).contains("\"label\":\"/srv/users/u/lib\"")
                .contains("\"url\":\"" + BASE + "/remove?path=/srv/users/u/lib\"")
                .contains("No folders inside")
                .doesNotContain("\"label\":\"Up\"");
    }

    @Test
    void aTruncatedListingSaysSo_andNoAdditionalDirectoriesSaysSoToo() throws Exception {
        var listing = new WorkingDirBrowser.Listing(null, "/home/u", "/home", List.of("/home/u/a"), true);

        String out = json(DirectoryPickerComponent.form(SESSION,
                AgentSession.start(AgentId.random(), UserId.of("u"), ConversationId.random()), listing));

        assertThat(out).contains("Only the first " + WorkingDirBrowser.MAX_ENTRIES + " folders are shown")
                .contains("None yet. Step into a folder above")
                .contains("\"label\":\"Add u\"")
                .doesNotContain("Anything under");
    }

    @Test
    void aRootIsNamedByItsPath() {
        assertThat(DirectoryPickerComponent.name("/srv/users/u/app")).isEqualTo("app");
        assertThat(DirectoryPickerComponent.name("/")).isEqualTo("/");
    }
}
