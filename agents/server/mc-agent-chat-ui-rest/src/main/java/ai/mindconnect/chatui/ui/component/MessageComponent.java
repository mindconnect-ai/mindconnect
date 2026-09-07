package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.ui.mvc.UiActions.streaming;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiTrigger;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One message in the conversation: who said it, when, what it cost in tokens,
 * and — for the user's own messages — the two actions that rewrite history
 * from that point (regenerate, delete-from-here).
 *
 * <p>Its own component so a list is not the only thing that can show a
 * message: a trace view, a sub-agent card or a search result can render the
 * same bubble without inheriting the list's machinery.
 */
public final class MessageComponent {

    private final UUID sessionId;
    private final AgentDefinition agent;
    private final Message message;
    private final boolean fromUser;
    private final DateTimeFormatter timeFormat;

    public MessageComponent(UUID sessionId, AgentDefinition agent, Message message,
                            boolean fromUser, DateTimeFormatter timeFormat) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.message = message;
        this.fromUser = fromUser;
        this.timeFormat = timeFormat;
    }

    /** The list item this message renders as. */
    public UiList.Item item() {
        return chatItem(message, fromUser);
    }


    /**
     * A user message that announced attachments (metadata written by the
     * runtime when the message was persisted) shows them as a line above the
     * text — rendering only; the stored text is what the user typed. The
     * media parts the message carries follow the text: an image as the
     * picture itself, served from the chat's file endpoint; a document as
     * its name.
     */
    String withAttachmentChip(Message m) {
        var attached = ai.mindconnect.agent.service.prompt.AttachmentNotice.announcedBy(m);
        var removed = ai.mindconnect.agent.service.prompt.AttachmentNotice.detachedBy(m);
        StringBuilder out = new StringBuilder();
        if (!attached.isEmpty()) out.append(icon("paperclip")).append(" *").append(String.join(", ", attached)).append("*\n\n");
        if (!removed.isEmpty()) out.append(icon("trash-2")).append(" *").append(String.join(", ", removed)).append(" removed*\n\n");
        out.append(m.content() == null ? "" : m.content());
        if (m.parts() != null) {
            for (var part : m.parts()) {
                if (part instanceof ai.mindconnect.message.domain.ContentPart.Image image) {
                    // A linked image: the thumbnail (sized by the chat's CSS)
                    // opens the original in a new tab — the markdown renderer
                    // gives every link target="_blank".
                    String url = contentUrl(image.fileId());
                    out.append("\n\n[![").append(markdownSafe(image.name())).append("](")
                            .append(url).append(")](").append(url).append(")");
                } else if (part instanceof ai.mindconnect.message.domain.ContentPart.File file) {
                    out.append("\n\n").append(icon("file-text")).append(" *").append(markdownSafe(file.name())).append("*");
                }
            }
        }
        return out.toString();
    }

    /** Where the chat serves a file it holds, inline — see {@code ChatFilesUiController#content}. */
    private String contentUrl(String fileId) {
        return "/chat/api/sessions/" + sessionId + "/chat-files/"
                + java.net.URLEncoder.encode(fileId, java.nio.charset.StandardCharsets.UTF_8) + "/content";
    }

    /**
     * A sprite icon inside markdown — the same {@code <svg><use>} the framework's
     * icon renderer emits, so it sizes and colours with the surrounding text.
     * Markdown passes inline HTML through; the name is a sprite id, never user input.
     */
    private static String icon(String name) {
        return "<svg class=\"sui-icon\" aria-hidden=\"true\"><use href=\"/sui/icons.svg#" + name + "\"></use></svg>";
    }

    /** A file name inside markdown link syntax: brackets and parentheses would end it early. */
    private static String markdownSafe(String name) {
        return name == null ? "" : name.replaceAll("[\\[\\]()*_`]", "");
    }

    private UiList.Item chatItem(Message m, boolean isUser) {
        if (ai.mindconnect.agent.tools.attachment.ViewAttachmentTool.insertedBy(m)) {
            return reshownItem(m);
        }
        String speaker = isUser ? "You" : agent.name();
        String time    = timeFormat.format(m.sentAt());
        String label   = speaker + "  [" + time + "]" + messageTokenSuffix(m);
        String css     = isUser ? "user-message" : "bot-message";
        int seq        = m.sequenceNum();

        var item = UiList.Item.of(m.id().toString(), label)
                .content(UiMarkdown.of("msg-" + m.id(), withAttachmentChip(m)).withCssClass(css));

        // Regenerate (USER messages only): delete this message + everything
        // after it, then re-run the turn (streaming) with the same text. Uses
        // the STREAM behaviour so the live tokens/task-cards flow exactly like
        // a normal send.
        if (isUser) {
            item.action(UiAction.icon("regen-" + m.id(), "Regenerate").icon("refresh-cw")
                    .confirm("Delete the response(s) after this message and generate a new one?")
                    // Plain dispatch — the regenerated turn streams on the
                    // session's stream like any other.
                    .onClick(trigger(on(ChatUiController.class).regenerate(sessionId, seq))));
        }

        // Delete-from-here: remove this message and every message after it.
        // toSeq = MAX_VALUE → the range delete runs to the end of the
        // conversation. Sub-agent sessions are not cleaned up.
        item.action(UiAction.icon("delete-" + m.id(), "Delete from here").icon("trash-2")
                .style(UiAction.Style.DANGER)
                .confirm("Delete this message and all following messages?")
                .onClick(trigger(on(ChatUiController.class)
                        .deleteMessages(sessionId, seq, Integer.MAX_VALUE, null))));
        return item;
    }
    /**
     * A message the runtime inserted on the user's behalf — an attachment
     * shown to the assistant again at its request — is not the user's, and
     * the picture is already in the bubble it came with: one muted line
     * naming the file, no thumbnail, no actions.
     */
    private UiList.Item reshownItem(Message m) {
        Object name = m.metadata() == null ? null
                : m.metadata().get(ai.mindconnect.agent.tools.attachment.ViewAttachmentTool.ATTACHMENT);
        String line = icon("repeat") + " *" + markdownSafe(name == null ? "attachment" : name.toString())
                + "* shown to the assistant again  [" + timeFormat.format(m.sentAt()) + "]";
        return UiList.Item.of(m.id().toString(), "")
                .content(UiMarkdown.of("msg-" + m.id(), line).withCssClass("reshown-message"));
    }

    /** " · 42 tok" for a single message; empty when not counted. */
    private String messageTokenSuffix(Message m) {
        Integer t = m.tokenCount();
        if (t == null || t <= 0) return "";
        return "  ·  " + String.format("%,d", t) + " tok";
    }}
