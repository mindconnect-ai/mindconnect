package ai.mindconnect.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /cd} and {@code /add-dir} mean a relative path from where the session works, not from the JVM's start directory. */
class DirectoryArgumentTest {

    @Test
    void aRelativePathIsResolvedAgainstTheWorkingDirectory() {
        assertThat(DirectoryArgument.resolve("sub", "/home/u/x")).isEqualTo("/home/u/x/sub");
        assertThat(DirectoryArgument.resolve("..", "/home/u/x/sub")).isEqualTo("/home/u/x");
        assertThat(DirectoryArgument.resolve("../y/./z", "/home/u/x/sub")).isEqualTo("/home/u/x/y/z");
        assertThat(DirectoryArgument.resolve(".", "/home/u/x")).isEqualTo("/home/u/x");
        assertThat(DirectoryArgument.resolve("  src  ", "/srv/app")).as("trimmed").isEqualTo("/srv/app/src");
    }

    @Test
    void absoluteAndHomePathsGoAsTyped() {
        assertThat(DirectoryArgument.resolve("/opt/lib", "/home/u/x")).isEqualTo("/opt/lib");
        assertThat(DirectoryArgument.resolve("~/src", "/home/u/x")).isEqualTo("~/src");
        assertThat(DirectoryArgument.resolve("~", "/home/u/x")).isEqualTo("~");
        assertThat(DirectoryArgument.resolve("$HOME/src", "/home/u/x")).isEqualTo("$HOME/src");
    }

    @Test
    void withoutAWorkingDirectoryOrInputNothingIsResolved() {
        assertThat(DirectoryArgument.resolve("sub", null)).isEqualTo("sub");
        assertThat(DirectoryArgument.resolve("sub", " ")).isEqualTo("sub");
        assertThat(DirectoryArgument.resolve("", "/home/u/x")).isEmpty();
        assertThat(DirectoryArgument.resolve(null, "/home/u/x")).isNull();
    }
}
