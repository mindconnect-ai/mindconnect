package ai.mindconnect.filerepo;

import java.nio.file.Path;

/**
 * Run in a separate JVM by {@link FileRepoTest}: opens partition {@code args[1]}
 * of the data directory {@code args[0]}. Exit code 0 when that worked, 3 when it
 * was refused.
 */
public final class OpenDataDirectory {

    private OpenDataDirectory() {
    }

    public static void main(String[] args) {
        try {
            FileRepo.open(Path.of(args[0]), args[1]);
            System.out.println("opened");
            System.exit(0);
        } catch (FileRepoException e) {
            System.out.println(e.getMessage());
            System.exit(3);
        }
    }
}
