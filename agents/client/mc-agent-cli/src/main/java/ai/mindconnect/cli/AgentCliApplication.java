package ai.mindconnect.cli;

import ai.mindconnect.agent.adapter.config.DefaultAgentRuntimeConfig;
import ai.mindconnect.agent.adapter.config.TodoToolsConfig;
import ai.mindconnect.message.adapter.file.MessageRepositoryConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    CliConfig.class,
    MessageRepositoryConfig.class,
    DefaultAgentRuntimeConfig.class,
    TodoToolsConfig.class
})
public class AgentCliApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCliApplication.class, args);
    }
}
