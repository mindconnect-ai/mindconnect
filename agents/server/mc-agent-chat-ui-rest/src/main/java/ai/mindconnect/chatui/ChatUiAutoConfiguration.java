package ai.mindconnect.chatui;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Brings the chat surface up wherever this jar is on the classpath — the
 * controllers under {@code /chat/api/**} and the stream registry behind them.
 *
 * <p>Auto-configuration rather than a package the host has to remember to
 * scan: the admin UI embeds the chat, and a standalone chat app should need
 * nothing but the dependency. Same pattern as the embedded workflow admin.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ComponentScan(basePackages = {
        "ai.mindconnect.chatui.ui.controller",
        "ai.mindconnect.chatui.service"
})
public class ChatUiAutoConfiguration {
}
