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
     * text — rendering only; the stored text is what the user typed.
     */
    private static String withAttachmentChip(Message m) {
        var files = ai.mindconnect.agent.service.prompt.AttachmentNotice.announcedBy(m);
        if (files.isEmpty()) return m.content();
        return "📎 *" + String.join(", ", files) + "*\n\n" + m.content();
    }

    private UiList.Item chatItem(Message m, boolean isUser) {
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
            item.action(UiAction.icon("regen-" + m.id(), "🔄")
                    .confirm("Delete the response(s) after this message and generate a new one?")
                    // Plain dispatch — the regenerated turn streams on the
                    // session's stream like any other.
                    .onClick(trigger(on(ChatUiController.class).regenerate(sessionId, seq))));
        }

        // Delete-from-here: remove this message and every message after it.
        // toSeq = MAX_VALUE → the range delete runs to the end of the
        // conversation. Sub-agent sessions are not cleaned up.
        item.action(UiAction.icon("delete-" + m.id(), "🗑")
                .style(UiAction.Style.DANGER)
                .confirm("Delete this message and all following messages?")
                .onClick(trigger(on(ChatUiController.class)
                        .deleteMessages(sessionId, seq, Integer.MAX_VALUE, null))));
        return item;
    }
    /** " · 42 tok" for a single message; empty when not counted. */
    private String messageTokenSuffix(Message m) {
        Integer t = m.tokenCount();
        if (t == null || t <= 0) return "";
        return "  ·  " + String.format("%,d", t) + " tok";
    }}
