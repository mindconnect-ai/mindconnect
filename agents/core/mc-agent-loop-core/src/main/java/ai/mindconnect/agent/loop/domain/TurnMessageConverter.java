package ai.mindconnect.agent.loop.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;

/**
 * A {@link TurnMessage} as a JSON object and back — the one line a message
 * is in a session file. Every part and every media source carries a
 * {@code kind}, so the data says what it holds without naming a Java class.
 *
 * <p>Plain maps and lists, no JSON library: the same shape goes into a
 * session file and into a task payload.
 */
public final class TurnMessageConverter {

    public Map<String, Object> toJson(TurnMessage message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", message.id());
        json.put("turnId", message.turnId());
        json.put("type", message.type().name());
        json.put("content", message.content().stream().map(TurnMessageConverter::partToJson).toList());
        json.put("toolCalls", message.toolCalls().stream().map(call -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("callId", call.callId());
            entry.put("name", call.name());
            entry.put("arguments", call.arguments());
            return entry;
        }).toList());
        json.put("metadata", message.metadata());
        return json;
    }

    @SuppressWarnings("unchecked")
    public TurnMessage fromJson(Map<String, Object> json) {
        return new TurnMessage(
                (String) json.get("id"),
                (String) json.get("turnId"),
                TurnMessage.Type.valueOf((String) json.get("type")),
                ((List<Map<String, Object>>) json.get("content")).stream()
                        .map(TurnMessageConverter::partFromJson).toList(),
                ((List<Map<String, Object>>) json.get("toolCalls")).stream()
                        .map(call -> new TurnMessage.ToolCall((String) call.get("callId"),
                                (String) call.get("name"), (Map<String, Object>) call.get("arguments")))
                        .toList(),
                (Map<String, Object>) json.get("metadata"));
    }

    private static Map<String, Object> partToJson(ContentPart part) {
        return switch (part) {
            case ContentPart.Text text -> Map.of("kind", "text", "text", text.text());
            case ContentPart.Image image -> {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("kind", "image");
                json.put("source", sourceToJson(image.source()));
                json.put("detail", image.detail().name());
                if (image.name() != null) {
                    json.put("name", image.name());
                }
                yield json;
            }
            case ContentPart.Audio audio -> Map.of("kind", "audio", "source", sourceToJson(audio.source()));
            case ContentPart.Document document -> Map.of("kind", "document",
                    "source", sourceToJson(document.source()), "name", document.name());
        };
    }

    @SuppressWarnings("unchecked")
    private static ContentPart partFromJson(Map<String, Object> json) {
        String kind = (String) json.get("kind");
        return switch (kind) {
            case "text" -> new ContentPart.Text((String) json.get("text"));
            case "image" -> new ContentPart.Image(sourceFromJson((Map<String, Object>) json.get("source")),
                    ContentPart.Image.Detail.valueOf((String) json.get("detail")), (String) json.get("name"));
            case "audio" -> new ContentPart.Audio(sourceFromJson((Map<String, Object>) json.get("source")));
            case "document" -> new ContentPart.Document(
                    sourceFromJson((Map<String, Object>) json.get("source")), (String) json.get("name"));
            default -> throw new IllegalArgumentException("Unknown kind of content part: " + kind);
        };
    }

    private static Map<String, Object> sourceToJson(MediaSource source) {
        return switch (source) {
            case MediaSource.Url url -> Map.of("kind", "url", "url", url.url());
            case MediaSource.FileId file -> Map.of("kind", "file", "fileId", file.fileId());
            case MediaSource.Inline inline -> Map.of("kind", "inline",
                    "data", inline.base64Data(), "mediaType", inline.mediaType());
        };
    }

    private static MediaSource sourceFromJson(Map<String, Object> json) {
        String kind = (String) json.get("kind");
        return switch (kind) {
            case "url" -> new MediaSource.Url((String) json.get("url"));
            case "file" -> new MediaSource.FileId((String) json.get("fileId"));
            case "inline" -> new MediaSource.Inline((String) json.get("data"), (String) json.get("mediaType"));
            default -> throw new IllegalArgumentException("Unknown kind of media source: " + kind);
        };
    }
}
