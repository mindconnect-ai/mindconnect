package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiMenuButton;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiText;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The composer's "+": everything you can add to a conversation, in one
 * popover.
 *
 * <p>The "+" used to be a single button that opened the attach dialog, which
 * made files the only thing a chat could be given from the composer — the
 * tools and the sub-agents were two clicks deep in a settings dialog named
 * after the model. They are the same kind of decision as attaching a file
 * ("what does this chat get to work with?"), so they sit in the same menu,
 * and the settings dialog keeps the ones that are genuinely about the model.
 *
 * <p>Four entries, each opening the picker that belongs to it. The badges
 * carry the state the menu is closed on: how many files hang on the
 * conversation and how many of them are pictures, how many tools it offers,
 * how many agents it may call. A count of zero shows nothing
 * rather than a "0" — an empty badge is noise on a chat that has attached
 * nothing.
 */
public final class ChatPlusMenuComponent {

    private ChatPlusMenuComponent() {}

    /** The css class the stylesheet parks in the composer's bottom-left corner. */
    public static final String CSS_CLASS = "chat-plus";

    /** Stable per-session id, so a REPLACE of the composer keeps the menu's identity. */
    public static String id(SessionId sessionId) {
        return "chat-plus-" + sessionId.value();
    }

    /**
     * What the menu's badges count.
     *
     * @param documents how many files that are not pictures hang on this conversation
     * @param images    how many pictures hang on it
     * @param tools     how many tools the chat offers up front
     * @param subAgents how many agents the chat may hand work to
     */
    public record Counts(int documents, int images, int tools, int subAgents) {
        public static final Counts NONE = new Counts(0, 0, 0, 0);

        /** Every file on the conversation, whatever its kind — what the "+" itself carries. */
        public int files() {
            return documents + images;
        }
    }

    /** The node id of the file count parked on the "+" itself. */
    public static String countId(SessionId sessionId) {
        return id(sessionId) + "-count";
    }

    /** The css class of that count. */
    public static final String COUNT_CSS_CLASS = "chat-plus-count";

    /**
     * How many files hang on the conversation, as a badge over the "+" — so it
     * is readable while typing, without opening the menu. {@code null} when
     * there are none: the "+" of a fresh chat carries nothing. A sibling of
     * the menu rather than part of it, because a menu button has no badge of
     * its own; the stylesheet lays it over the button's corner.
     */
    public static UiText fileCount(SessionId sessionId, Counts counts) {
        String files = count(counts.files());
        if (files == null) return null;
        return UiText.of(countId(sessionId), files).<UiText>withCssClass(COUNT_CSS_CLASS);
    }

    public static UiMenuButton menu(SessionId sessionId, Counts counts) {
        var menu = UiMenuButton.of(id(sessionId));
        menu.icon("plus");
        // Opens rightwards: the "+" sits at the composer's left edge, and an
        // END-aligned popover would hang off the window on a narrow screen.
        menu.align(UiMenuButton.Align.START);
        menu.setTitle("Add files, tools or a sub-agent");
        menu.withCssClass(CSS_CLASS);

        menu.item(UiMenuItem.of("plus-files", "Upload files").icon("paperclip")
                .badge(count(counts.documents()))
                .onClick(trigger(on(ChatUiController.class)
                        .attachDialog(sessionId.value(), null, null))));
        menu.item(UiMenuItem.of("plus-images", "Add images").icon("image")
                .badge(count(counts.images()))
                .onClick(trigger(on(ChatUiController.class)
                        .attachDialog(sessionId.value(), ChatUiController.ATTACH_IMAGES, null))));
        menu.item(UiMenuItem.divider());
        menu.item(UiMenuItem.of("plus-tools", "Tools").icon("wrench")
                .badge(count(counts.tools()))
                .onClick(trigger(on(ChatUiController.class)
                        .toolsDialog(sessionId.value(), null))));
        menu.item(UiMenuItem.of("plus-agents", "Sub-agents").icon("bot")
                .badge(count(counts.subAgents()))
                .onClick(trigger(on(ChatUiController.class)
                        .subAgentsDialog(sessionId.value(), null))));
        return menu;
    }

    /** A count worth showing, or nothing at all. */
    private static String count(int value) {
        return value <= 0 ? null : String.valueOf(value);
    }
}
