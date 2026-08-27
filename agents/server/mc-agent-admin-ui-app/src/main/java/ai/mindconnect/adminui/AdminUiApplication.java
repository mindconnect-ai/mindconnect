package ai.mindconnect.adminui;

import ai.mindconnect.agent.adapter.config.DefaultAgentRuntimeConfig;
import ai.mindconnect.agent.adapter.config.TodoToolsConfig;
import ai.mindconnect.adminui.config.InfrastructureConfig;
import ai.mindconnect.adminui.config.LlmConfig;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.adapter.file.MessageRepositoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan(basePackages = {
    "ai.mindconnect.adminui",
    "ai.mindconnect.agentrest"
})
@Import({
    InfrastructureConfig.class,
    LlmConfig.class,
    MessageRepositoryConfig.class,
    DefaultAgentRuntimeConfig.class,
    TodoToolsConfig.class
})
@Slf4j
public class AdminUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminUiApplication.class, args);
    }

    @Bean
    Namespace namespace() {
        return new Namespace("local");
    }


}
