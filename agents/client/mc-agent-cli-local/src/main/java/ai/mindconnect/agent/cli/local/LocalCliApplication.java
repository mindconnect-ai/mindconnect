package ai.mindconnect.agent.cli.local;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import ai.mindconnect.agent.jackson.IdJsonModule;
import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.ModelSettingsResolver;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.common.util.McEnv;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Starts the harness in the directory it is called from.
 *
 * <pre>
 * mc-local [--agent NAME] [-p PROMPT]
 * </pre>
 *
 * Without {@code -p} it converses; with it, it runs the one turn and ends.
 * The model is OpenAI's, from {@code OPENAI_API_KEY} and {@code MC_MODEL}.
 */
public final class LocalCliApplication {

    private static final String DEFAULT_MODEL = "gpt-5-mini";

    private LocalCliApplication() {
    }

    public static void main(String[] args) {
        String agent = option(args, "--agent", LocalRuntime.DEFAULT_AGENT);
        String prompt = option(args, "-p", null);
        String apiKey = McEnv.get("OPENAI_API_KEY", "");
        if (apiKey.isBlank()) {
            System.err.println("OPENAI_API_KEY is not set.");
            System.exit(2);
        }
        String model = McEnv.get("MC_MODEL", DEFAULT_MODEL);
        Path home = Path.of(System.getProperty("user.home"));
        // Built before the runtime, whose factory does not exist yet; the id module keeps the session files as they were.
        JsonMapperFactory json = new JsonMapperFactory(List.of(new IdJsonModule(), new LlmJsonModule()));
        LocalRuntime local = new LocalRuntime(home, json);
        try (AgentRuntime runtime = local.builder()
                .model(new OpenAiChat(apiKey, model, json).chat())
                .plugin(modelOfLastResort(model))
                .build()) {
            Session session = runtime.sessions().open(local.sessionIn(
                    Path.of(""), UserId.of(System.getProperty("user.name")), agent));
            LocalCli cli = new LocalCli(runtime, session,
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
            if (prompt == null) {
                cli.converse();
            } else {
                cli.ask(prompt);
            }
        }
    }

    /** The weakest layer of the model settings: the model to use where no agent and no settings file names one. */
    private static Plugin modelOfLastResort(String model) {
        return new Plugin() {
            @Override
            public String name() {
                return "model-of-last-resort";
            }

            @Override
            public void install(PluginRegistry registry) {
                registry.contribute(ModelSettingsResolver.class, session -> List.of(ModelSettings.ofModel(model)));
            }
        };
    }

    private static String option(String[] args, String name, String otherwise) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return otherwise;
    }
}
