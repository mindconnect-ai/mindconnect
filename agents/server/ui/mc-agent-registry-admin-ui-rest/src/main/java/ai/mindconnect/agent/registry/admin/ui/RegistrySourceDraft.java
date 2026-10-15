package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;

import java.util.Map;

/**
 * What the add/edit form holds — every field a string, because that is what a
 * form posts back. Turning it into a {@link RegistrySource} happens in one
 * place, so the form and the API agree on what an empty field means.
 */
record RegistrySourceDraft(
        String id,
        String name,
        String repository,
        String ref,
        String indexPath,
        String tokenEnvVar,
        String baseUrl,
        boolean enabled
) {

    static RegistrySourceDraft empty() {
        return new RegistrySourceDraft(null, null, null, null, null, null, null, true);
    }

    static RegistrySourceDraft of(RegistrySource source) {
        return new RegistrySourceDraft(source.id().value(), source.name(),
                source.owner() + "/" + source.repo(), source.ref(), source.indexPath(),
                source.tokenEnvVar(), source.baseUrl(), source.enabled());
    }

    /** The draft as a form posts it back. */
    static RegistrySourceDraft fromForm(Map<String, Object> body) {
        return new RegistrySourceDraft(
                text(body.get("id")), text(body.get("name")), text(body.get("repository")),
                text(body.get("ref")), text(body.get("indexPath")), text(body.get("tokenEnvVar")),
                text(body.get("baseUrl")), bool(body.get("enabled")));
    }

    /**
     * The source this draft describes, keeping the id and version of
     * {@code existing} when there is one — an edit must not turn into a second
     * registry, and the version check is what stops two edits from both
     * winning.
     *
     * @throws IllegalArgumentException when the repository field names no repository
     */
    RegistrySource toSource(RegistrySource existing) {
        RegistrySource parsed = RegistrySource.of(repository);
        RegistrySourceId sourceId = existing != null ? existing.id()
                : id != null && !id.isBlank() ? RegistrySourceId.of(id) : parsed.id();
        String effectiveRef = ref != null && !ref.isBlank() ? ref : parsed.ref();
        return new RegistrySource(sourceId, name, parsed.owner(), parsed.repo(), effectiveRef,
                indexPath, tokenEnvVar, baseUrl, enabled,
                existing != null ? existing.version() : null);
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }

    private static boolean bool(Object value) {
        if (value == null) return true;
        if (value instanceof Boolean flag) return flag;
        return !"false".equalsIgnoreCase(value.toString());
    }
}
