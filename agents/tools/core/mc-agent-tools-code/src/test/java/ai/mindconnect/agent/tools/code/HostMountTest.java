package ai.mindconnect.agent.tools.code;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mount is a hole in the container's isolation, so the two rules that keep
 * it honest are worth pinning down: it identifies the container it belongs to,
 * and read-only is a different container from writable.
 */
class HostMountTest {

    @Test
    void noMountHasAKeyOfItsOwn() {
        // Not the empty string: "no mount" must never collide with a mount
        // whose path happens to stringify to nothing.
        assertThat(HostMount.key(null)).isEqualTo("-");
    }

    @Test
    void readOnlyAndWritableAreDifferentContainers() {
        Path dir = Path.of("/tmp/mc-host");

        String ro = HostMount.key(new HostMount(dir, true));
        String rw = HostMount.key(new HostMount(dir, false));

        assertThat(ro).isNotEqualTo(rw);
        assertThat(ro).endsWith(":ro");
        assertThat(rw).endsWith(":rw");
    }

    @Test
    void differentDirectoriesAreDifferentContainers() {
        assertThat(HostMount.key(new HostMount(Path.of("/tmp/a"), true)))
                .isNotEqualTo(HostMount.key(new HostMount(Path.of("/tmp/b"), true)));
    }

    @Test
    void theKeyIsAbsoluteSoTwoSpellingsOfOnePathAgree() {
        Path relative = Path.of("target");
        assertThat(HostMount.key(new HostMount(relative, true)))
                .isEqualTo(HostMount.key(new HostMount(relative.toAbsolutePath(), true)));
    }

    @Test
    void aMountWithoutADirectoryIsARefusal() {
        assertThatThrownBy(() -> new HostMount(null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describeNamesBothEndsAndWhichWayItOpens() {
        assertThat(new HostMount(Path.of("/home/u"), true).describe())
                .contains("/home/u").contains(HostMount.MOUNT_POINT).contains("read-only");
        assertThat(new HostMount(Path.of("/home/u"), false).describe()).contains("writable");
    }
}
