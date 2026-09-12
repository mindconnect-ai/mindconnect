package ai.mindconnect.agent.registry.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A registry this installation knows: a GitHub project holding an index and
 * the entity files beside it.
 *
 * <p>There is no registry server. A registry is a repository — fork it, send a
 * pull request, pin a tag — and everything a hosted catalogue would need
 * (review, history, ownership) is what Git already does. Reading one is a
 * plain HTTPS GET of raw files, so a private registry needs nothing but a
 * token.
 *
 * @param id          stable local address of this source
 * @param name        what to call it on screen; defaults to {@code owner/repo}
 * @param owner       GitHub user or organisation
 * @param repo        repository name
 * @param ref         branch, tag or commit to read; {@code main} when omitted.
 *                    Pin a tag for a registry you do not control — a branch is
 *                    whatever its owner pushed last
 * @param indexPath   path of the index inside the repository; {@code registry.json}
 * @param tokenEnvVar name of the environment variable holding a token for a
 *                    private repository, {@code null} for a public one. The
 *                    name, never the token: a store that can be exported must
 *                    not be able to leak one
 * @param baseUrl     raw-content host, {@code null} for github.com. Set it for a
 *                    GitHub Enterprise instance
 * @param enabled     a disabled source stays configured but is not read
 * @param version     the stored version this source was read with — optimistic
 *                    locking, see {@code ai.mindconnect.common.Versions}
 */
public record RegistrySource(
        RegistrySourceId id,
        String name,
        String owner,
        String repo,
        String ref,
        String indexPath,
        String tokenEnvVar,
        String baseUrl,
        boolean enabled,
        Long version
) {

    /** What a source reads when it names no ref of its own. */
    public static final String DEFAULT_REF = "main";

    /** What a source reads when it names no index of its own. */
    public static final String DEFAULT_INDEX_PATH = "registry.json";

    /** Raw content of a repository on github.com. */
    public static final String GITHUB_RAW_BASE_URL = "https://raw.githubusercontent.com";

    /** {@code owner/repo}, optionally {@code @ref} and {@code :indexPath}. */
    private static final Pattern SPEC = Pattern.compile(
            "(?<owner>[^/@:\\s]+)/(?<repo>[^/@:\\s]+)(?:@(?<ref>[^:\\s]+))?(?::(?<path>\\S+))?");

    public RegistrySource {
        owner = required(owner, "owner");
        repo = required(repo, "repo");
        ref = blankTo(ref, DEFAULT_REF);
        indexPath = stripLeadingSlash(blankTo(indexPath, DEFAULT_INDEX_PATH));
        baseUrl = blankTo(baseUrl, GITHUB_RAW_BASE_URL);
        tokenEnvVar = blankToNull(tokenEnvVar);
        name = blankTo(name, owner + "/" + repo);
    }

    /**
     * A source from the short form an operator types: {@code owner/repo},
     * {@code owner/repo@v1.2.0}, {@code owner/repo@main:catalog/registry.json}.
     * A full {@code https://github.com/owner/repo} URL is accepted too — it is
     * what the browser's address bar offers, and refusing it would be a riddle.
     *
     * @throws IllegalArgumentException when the text names no repository
     */
    public static RegistrySource of(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("A registry needs an owner/repo");
        }
        String text = spec.strip();
        text = text.replaceFirst("^https?://(www\\.)?github\\.com/", "");
        text = text.replaceFirst("\\.git$", "");
        Matcher matcher = SPEC.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "'" + spec + "' is not a registry: expected owner/repo[@ref][:index-path]");
        }
        String owner = matcher.group("owner");
        String repo = matcher.group("repo");
        return new RegistrySource(idFor(owner, repo), owner + "/" + repo, owner, repo,
                matcher.group("ref"), matcher.group("path"), null, null, true, null);
    }

    /**
     * The id a source parsed from {@code owner/repo} gets: the two names, made
     * into one that {@code EntityId} accepts. Readable, and the same every
     * time, so adding the same registry twice collides instead of piling up.
     */
    private static RegistrySourceId idFor(String owner, String repo) {
        String value = (owner + "-" + repo).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return RegistrySourceId.of(value);
    }

    /** {@code owner/repo@ref} — how a source names itself in a log or a report. */
    @JsonIgnore
    public String coordinates() {
        return owner + "/" + repo + "@" + ref;
    }

    /**
     * The repository's page on github.com, for a screen that wants to name
     * where this came from — {@code null} for a source read from another host,
     * whose web address does not follow from its raw-content one.
     */
    @JsonIgnore
    public String repositoryUrl() {
        return GITHUB_RAW_BASE_URL.equals(baseUrl)
                ? "https://github.com/" + owner + "/" + repo
                : null;
    }

    public RegistrySource withVersion(Long version) {
        return new RegistrySource(id, name, owner, repo, ref, indexPath, tokenEnvVar, baseUrl,
                enabled, version);
    }

    public RegistrySource withEnabled(boolean enabled) {
        return new RegistrySource(id, name, owner, repo, ref, indexPath, tokenEnvVar, baseUrl,
                enabled, version);
    }

    /** Jackson: trailing fields default, so sources written by older versions still load. */
    @JsonCreator
    static RegistrySource fromJson(
            @JsonProperty("id")          String id,
            @JsonProperty("name")        String name,
            @JsonProperty("owner")       String owner,
            @JsonProperty("repo")        String repo,
            @JsonProperty("ref")         String ref,
            @JsonProperty("indexPath")   String indexPath,
            @JsonProperty("tokenEnvVar") String tokenEnvVar,
            @JsonProperty("baseUrl")     String baseUrl,
            @JsonProperty("enabled")     Boolean enabled,
            @JsonProperty("version")     Long version) {
        return new RegistrySource(RegistrySourceId.of(id), name, owner, repo, ref, indexPath,
                tokenEnvVar, baseUrl, enabled == null || enabled, version);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A registry source needs a " + field);
        }
        return value.strip();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
