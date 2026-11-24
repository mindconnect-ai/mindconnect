package ai.mindconnect.agent.management.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

class DirectoryPluginTest {

    @TempDir
    Path data;

    /** Deleting a chat must not take files with it: a host that wants that says so itself. */
    @Test
    void a_deleted_session_keeps_its_directory_unless_the_host_contributes_the_cleanup() {
        PluginRegistry registry = new DefaultPluginRegistry();

        registry.install(new DirectoryPlugin(data, "", "", true, ""));

        assertThat(registry.all(SessionCleanup.class)).isEmpty();
    }
}
