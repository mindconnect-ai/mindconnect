package ai.mindconnect.agent.runtime.domain;

import java.util.Locale;
import java.util.Map;

/**
 * The media type as a provider wants to see it. Uploads carry whatever the
 * browser or tool reported: Windows registers {@code .jpg} as
 * {@code image/jpg} or {@code image/pjpeg} on some machines, old tooling
 * says {@code image/x-png} or {@code application/x-pdf}, and a charset
 * parameter may trail. Anthropic and OpenAI reject all of those with a 400
 * — {@code image/jpeg} is the one JPEG type they take — so every media type
 * that reaches a message part or a content block passes through here.
 */
public final class MediaTypes {

    private static final Map<String, String> ALIASES = Map.of(
            "image/jpg", "image/jpeg",
            "image/pjpeg", "image/jpeg",
            "image/x-png", "image/png",
            "application/x-pdf", "application/pdf");

    private MediaTypes() {
    }

    /**
     * Lower-cased, without parameters, with the known aliases folded onto
     * the canonical type; {@code null} and blank stay as they are.
     */
    public static String normalize(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return mediaType;
        String type = mediaType.trim().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) type = type.substring(0, semicolon).trim();
        return ALIASES.getOrDefault(type, type);
    }
}
