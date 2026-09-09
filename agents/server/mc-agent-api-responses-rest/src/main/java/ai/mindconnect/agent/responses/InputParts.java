package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.item.ContentPart;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The content parts of a user message, as OpenAI's Responses API defines
 * them, turned into the protocol's parts:
 *
 * <ul>
 *   <li>{@code input_text} — {@code text}</li>
 *   <li>{@code input_image} — one of {@code image_url} (an http(s) URL or a
 *       {@code data:} URL) or {@code file_id}; optional {@code detail}</li>
 *   <li>{@code input_file} — one of {@code file_data} (a {@code data:} URL,
 *       with {@code filename}), {@code file_url} or {@code file_id}</li>
 * </ul>
 *
 * <p>A URL is fetched here and carried inline, so the runtime sees every
 * source the same way; a data URL is decoded; a file id names a file the
 * client uploaded through {@code /v1/files}. A part of any other type is
 * refused — the alternative, an answer to the part of the question the
 * server understood, is worse than an error.
 */
final class InputParts {

    /** The most a fetched file may weigh — a document, not a dataset. */
    static final long MAX_FETCH_BYTES = 25L * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private InputParts() {}

    /** The parts of a message's {@code content}: a string is one text part. */
    static List<ContentPart> parse(JsonNode content) {
        if (content == null || content.isNull()) {
            return List.of();
        }
        if (content.isTextual()) {
            return List.of(new ContentPart.Text(content.asText()));
        }
        if (!content.isArray()) {
            throw new IllegalArgumentException("A message's 'content' is a string or an array of parts");
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode part : content) {
            parts.add(parsePart(part));
        }
        return parts;
    }

    static ContentPart parsePart(JsonNode part) {
        String type = text(part, "type");
        if (type == null) {
            throw new IllegalArgumentException("A content part needs a 'type' (input_text, input_image, input_file)");
        }
        return switch (type) {
            case "input_text", "text" -> new ContentPart.Text(orEmpty(text(part, "text")));
            case "input_image" -> image(part);
            case "input_file" -> file(part);
            default -> throw new IllegalArgumentException("Content parts of type '" + type
                    + "' are not supported; send input_text, input_image or input_file");
        };
    }

    private static ContentPart image(JsonNode part) {
        String fileId = text(part, "file_id");
        String url = text(part, "image_url");
        if (url == null && part.get("image_url") != null && part.get("image_url").isObject()) {
            // The chat-completions spelling, {"image_url": {"url": …}} — met in the wild.
            url = text(part.get("image_url"), "url");
        }
        ContentPart.MediaSource source;
        if (fileId != null) {
            source = new ContentPart.MediaSource.FileId(fileId);
        } else if (url != null) {
            source = url.startsWith("data:") ? dataUrl(url) : fetched(url, null);
        } else {
            throw new IllegalArgumentException("An input_image needs 'image_url' (an http(s) or data: URL) or 'file_id'");
        }
        return new ContentPart.Image(source, detail(text(part, "detail")));
    }

    private static ContentPart file(JsonNode part) {
        String fileId = text(part, "file_id");
        String data = text(part, "file_data");
        String url = text(part, "file_url");
        String name = text(part, "filename");
        if (fileId != null) {
            return new ContentPart.Document(new ContentPart.MediaSource.FileId(fileId), name);
        }
        if (data != null) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("An input_file with 'file_data' needs a 'filename'");
            }
            ContentPart.MediaSource.Inline inline = data.startsWith("data:") ? dataUrl(data)
                    // Bare base64, as some clients send it: the name says what it is.
                    : new ContentPart.MediaSource.Inline(data, mediaTypeFor(name));
            return new ContentPart.Document(inline, name);
        }
        if (url != null) {
            return new ContentPart.Document(fetched(url, name), name != null ? name : nameFrom(url));
        }
        throw new IllegalArgumentException("An input_file needs 'file_data' (with 'filename'), 'file_url' or 'file_id'");
    }

    /** {@code data:<media type>;base64,<data>} — the media type may be missing, the encoding must be base64. */
    static ContentPart.MediaSource.Inline dataUrl(String url) {
        int comma = url.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Not a data URL: no ',' between the header and the data");
        }
        String header = url.substring("data:".length(), comma);
        String payload = url.substring(comma + 1).strip();
        String[] fields = header.split(";");
        String mediaType = fields[0].isBlank() ? "application/octet-stream" : fields[0].strip();
        boolean base64 = false;
        for (String f : fields) {
            if (f.strip().equalsIgnoreCase("base64")) base64 = true;
        }
        if (!base64) {
            throw new IllegalArgumentException("A data URL must be base64-encoded (data:<type>;base64,…)");
        }
        try {
            Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The data URL's payload is not valid base64: " + e.getMessage());
        }
        return new ContentPart.MediaSource.Inline(payload, mediaType);
    }

    /** An http(s) URL, fetched now and carried inline — the way OpenAI reads a file_url or image_url. */
    static ContentPart.MediaSource.Inline fetched(String url, String name) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http(s) URLs can be fetched: " + url);
        }
        try {
            HttpResponse<byte[]> response = HTTP.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("Fetching " + url + " answered HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_FETCH_BYTES) {
                throw new IllegalArgumentException("The file at " + url + " is larger than "
                        + (MAX_FETCH_BYTES / (1024 * 1024)) + " MB");
            }
            String mediaType = response.headers().firstValue("content-type")
                    .map(ct -> ct.split(";")[0].strip())
                    .filter(ct -> !ct.isBlank())
                    .orElse(mediaTypeFor(name != null ? name : nameFrom(url)));
            return new ContentPart.MediaSource.Inline(Base64.getEncoder().encodeToString(body), mediaType);
        } catch (IOException e) {
            throw new IllegalArgumentException("Fetching " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Fetching " + url + " was interrupted");
        }
    }

    static ContentPart.Image.Detail detail(String raw) {
        if (raw == null) return ContentPart.Image.Detail.AUTO;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "low" -> ContentPart.Image.Detail.LOW;
            case "high" -> ContentPart.Image.Detail.HIGH;
            default -> ContentPart.Image.Detail.AUTO;
        };
    }

    /** The last path segment of a URL, or a generic name. */
    static String nameFrom(String url) {
        String path = URI.create(url).getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) return "download";
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** A media type from a file name's extension — for a client that sent bare base64 or an untyped URL. */
    static String mediaTypeFor(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "html", "htm" -> "text/html";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
