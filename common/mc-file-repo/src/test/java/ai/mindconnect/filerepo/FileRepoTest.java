package ai.mindconnect.filerepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRepoTest {

    @TempDir
    Path dir;

    @Test
    void everySpellingOfAPartitionOpensTheSameInstance() throws Exception {
        Path data = dir.resolve("data");
        FileRepo repo = FileRepo.open(data, "ns");
        Files.createDirectories(dir.resolve("other"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), data);

        assertThat(FileRepo.open(dir.resolve("other/../data"), "ns")).isSameAs(repo);
        assertThat(FileRepo.open(link, "ns")).isSameAs(repo);
        assertThat(FileRepo.open(data, "other-ns")).isNotSameAs(repo);
        assertThat(repo.root()).isEqualTo(data.resolve("ns").toRealPath());
    }

    @Test
    void aPartitionIsOneDirectoryName() {
        Path data = dir.resolve("data");

        for (String bad : new String[]{"", " ", ".", "..", "a/b", "a\\b", null}) {
            assertThatThrownBy(() -> FileRepo.open(data, bad))
                    .as("partition %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void pathsLeadingOutOfThePartitionAreRefused() {
        FileRepo repo = FileRepo.open(dir.resolve("data"), "ns");

        assertThat(repo.resolve("sessions/s1/session.json")).startsWithRaw(repo.root());
        assertThatThrownBy(() -> repo.resolve("../other-ns/sessions/s1/session.json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.resolve("sessions/../../escape.json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.resolve("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesOnlyTemporaryFilesOldEnoughToBeLeftovers() throws Exception {
        FileRepo repo = FileRepo.open(dir.resolve("data"), "ns");
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        Path leftover = file(repo.resolve("sessions/s1/.session.json.1.tmp"), longAgo);
        Path inProgress = file(repo.resolve("sessions/s1/.session.json.2.tmp"), Instant.now());
        Path document = file(repo.resolve("sessions/s1/session.json"), longAgo);

        repo.deleteTemporaryFilesOlderThan(Duration.ofMinutes(1));

        assertThat(leftover).doesNotExist();
        assertThat(inProgress).exists();
        assertThat(document).exists();
        assertThat(repo.resolve(FileRepo.LOCK_FILE)).exists();
    }

    @Test
    void aSecondProcessOnTheSamePartitionIsRefused() throws Exception {
        Path data = dir.resolve("data");
        FileRepo.open(data, "ns");

        ChildRun run = openInChildProcess(data, "ns");

        assertThat(run.exitCode()).as(run.output()).isEqualTo(3);
        assertThat(run.output()).contains("in use by another process");
    }

    @Test
    void anotherPartitionOfTheSameDataDirectoryOpensInASecondProcess() throws Exception {
        Path data = dir.resolve("data");
        FileRepo.open(data, "ns");

        ChildRun run = openInChildProcess(data, "other-ns");

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(data.resolve(FileRepo.LOCK_FILE)).as("the data directory itself is never locked").doesNotExist();
    }

    private record ChildRun(int exitCode, String output) { }

    private static ChildRun openInChildProcess(Path base, String partition) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Arrays.stream(new Class<?>[]{FileRepo.class, OpenDataDirectory.class, LoggerFactory.class})
                .map(FileRepoTest::locationOf)
                .distinct()
                .collect(Collectors.joining(File.pathSeparator));
        Process process = new ProcessBuilder(java, "-cp", classpath, OpenDataDirectory.class.getName(),
                base.toString(), partition)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("child JVM finished").isTrue();
        return new ChildRun(process.exitValue(), output);
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path file(Path path, Instant modified) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{}");
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }
}
