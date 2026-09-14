package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.model.UiMenuButton;
import ai.mindconnect.ui.model.UiMenuItem;

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
 * conversation, how many tools it offers. A count of zero shows nothing
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
     * @param attachments how many files hang on this conversation
     * @param tools       how many tools the chat offers up front
     */
    public static UiMenuButton menu(SessionId sessionId, int attachments, int tools) {
        var menu = UiMenuButton.of(id(sessionId));
        menu.icon("plus");
        // Opens rightwards: the "+" sits at the composer's left edge, and an
        // END-aligned popover would hang off the window on a narrow screen.
        menu.align(UiMenuButton.Align.START);
        menu.setTitle("Add files, tools or a sub-agent");
        menu.withCssClass(CSS_CLASS);

        menu.item(UiMenuItem.of("plus-files", "Upload files").icon("paperclip")
                .badge(count(attachments))
                .onClick(trigger(on(ChatUiController.class)
                        .attachDialog(sessionId.value(), null, null))));
        menu.item(UiMenuItem.of("plus-images", "Add images").icon("image")
                .onClick(trigger(on(ChatUiController.class)
                        .attachDialog(sessionId.value(), ChatUiController.ATTACH_IMAGES, null))));
        menu.item(UiMenuItem.divider());
        menu.item(UiMenuItem.of("plus-tools", "Tools").icon("wrench")
                .badge(count(tools))
                .onClick(trigger(on(ChatUiController.class)
                        .toolsDialog(sessionId.value(), null))));
        menu.item(UiMenuItem.of("plus-agents", "Sub-agents").icon("bot")
                .onClick(trigger(on(ChatUiController.class)
                        .subAgentsDialog(sessionId.value(), null))));
        return menu;
    }

    /** A count worth showing, or nothing at all. */
    private static String count(int value) {
        return value <= 0 ? null : String.valueOf(value);
    }
}
