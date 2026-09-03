package ai.mindconnect.adminui.ui;

import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * What this server build is, for the header's version label and the About
 * dialog behind it. Everything comes out of the jar: Spring Boot's
 * {@code META-INF/build-info.properties} (version, build time),
 * {@code git.properties} (commit, branch) and the repository's
 * {@code META-INF/CHANGELOG.md} — the admin-ui app packages all three. An IDE
 * run has none of them; then there is no label and the dialog says so.
 */
@Component
public class BuildInfo {

    private static final DateTimeFormatter MINUTE_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final BuildProperties build;
    private final GitProperties git;
    private final String changelog;

    public BuildInfo(Optional<BuildProperties> build, Optional<GitProperties> git) {
        this.build = build.orElse(null);
        this.git = git.orElse(null);
        this.changelog = readChangelog();
    }

    /** False for a run straight from the IDE, where nothing was packaged. */
    public boolean isKnown() {
        return build != null;
    }

    public String version() {
        return build == null ? null : build.getVersion();
    }

    /**
     * The header label — short, because the header is: {@code v0.3.1} for a
     * release, {@code 0.3.1-SNAPSHOT · dd8d540} for a snapshot. A branch
     * build's branch is in the dialog; in the header it would take the room
     * of the whole brand.
     */
    public String label() {
        String version = version();
        if (version == null) return null;
        if (!version.endsWith("-SNAPSHOT")) return "v" + version;
        String base = version.substring(0, version.indexOf('-')) + "-SNAPSHOT";
        String commit = commit();
        return commit == null ? base : base + " · " + commit;
    }

    public String builtAt() {
        if (build == null) return null;
        Instant time = build.getTime();
        return time == null ? null : MINUTE_UTC.format(time);
    }

    public String commit() {
        return git == null ? null : git.getShortCommitId();
    }

    public String branch() {
        return git == null ? null : git.getBranch();
    }

    /**
     * The changelog section that belongs to this build: {@code [<version>]}
     * for a release, {@code [Unreleased]} for a snapshot — what the branch has
     * changed and not released yet. Null when the jar carries no changelog.
     */
    public String changelogSection() {
        return changelog == null || version() == null ? null : section(changelog, version());
    }

    private static String readChangelog() {
        ClassPathResource resource = new ClassPathResource("META-INF/CHANGELOG.md");
        if (!resource.exists()) return null;
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** One section of a Keep-a-Changelog file; the heading line is kept. */
    static String section(String changelog, String version) {
        String heading = version.endsWith("-SNAPSHOT") ? "## [Unreleased]" : "## [" + version + "]";
        String out = between(changelog, heading);
        // The release workflow renames [Unreleased] to the version before it
        // builds the jar, so a release normally finds its own heading. A
        // release built by hand has not been renamed - then [Unreleased] is
        // the section that was about to become it.
        if (out.isEmpty() && !version.endsWith("-SNAPSHOT")) {
            heading = "## [Unreleased]";
            out = between(changelog, heading);
        }
        if (out.isEmpty()) return heading + "\n\nNo such section in the changelog this build carries.";
        if (out.lines().skip(1).allMatch(String::isBlank)) {
            return out.strip() + "\n\nNo entries — nothing is written up for this build yet.";
        }
        return out.strip();
    }

    /** The heading line and everything up to the next version heading; empty when absent. */
    private static String between(String changelog, String heading) {
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String line : changelog.split("\\R")) {
            if (line.startsWith(heading)) {
                inside = true;
                out.append(line).append('\n');
            } else if (inside && line.startsWith("## [")) {
                break;
            } else if (inside) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
