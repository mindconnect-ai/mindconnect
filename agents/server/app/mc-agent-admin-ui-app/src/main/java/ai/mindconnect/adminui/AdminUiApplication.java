package ai.mindconnect.adminui;

import ai.mindconnect.adminui.config.InfrastructureConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * The admin UI. The agent runtime — repositories, LLM layer, tools, turn
 * loop — comes from {@code mc-agent-starter-runtime}, built from the same
 * features the embedded builder installs and configured from the
 * {@code mindconnect.*} properties; what is left here is the UI's own.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
    "ai.mindconnect.adminui",
    "ai.mindconnect.agentrest"
})
@Import({
    InfrastructureConfig.class,
    ai.mindconnect.agent.responses.config.ResponsesApiConfiguration.class
})
@Slf4j
public class AdminUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminUiApplication.class, args);
    }
}
