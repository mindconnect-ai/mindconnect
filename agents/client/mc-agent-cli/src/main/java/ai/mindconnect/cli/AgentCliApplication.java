package ai.mindconnect.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The terminal client. In local mode the agent runtime comes from
 * {@code mc-agent-starter-runtime}, on files, without an encryption key —
 * a personal tool on a personal machine keeps its credentials plain.
 */
@SpringBootApplication
@Import(CliConfig.class)
public class AgentCliApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCliApplication.class, args);
    }
}
