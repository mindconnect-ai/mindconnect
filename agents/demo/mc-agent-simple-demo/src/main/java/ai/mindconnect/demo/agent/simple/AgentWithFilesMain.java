package ai.mindconnect.demo.agent.simple;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.fileupload.FileUploadFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.llm.domain.LlmConfig;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The chat-upload flow, embedded: attach a file to a session and ask about
 * its content. {@link AgentRuntime#attachFile} chunks OpenAI-style
 * (800/400 tokens), embeds via the {@code embeddings} LlmConfig, stores the
 * vectors in the session's vector store and announces the file to the agent —
 * which then answers through the {@code vector_search} tool.
 *
 * <p>Two features on top of the core: {@link ToolsFeature} for
 * {@code vector_search}, {@link FileUploadFeature} for the file store and
 * the attach path (it needs the tools feature, and says so if it is missing).
 * File persistence this time, under a temp directory.
 *
 * <p>Needs LM Studio (or compatible) with a chat model and an embedding
 * model loaded — see {@code demo-llm-config.json} and the embeddings config
 * below.
 */
public class AgentWithFilesMain {

    private static final String POLICY = """
            # Travel Policy
            Employees may book business class only for flights longer than six hours.

            # Expense Rules
            Receipts above 50 CHF must be uploaded within 14 days.
            """;

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("mc-agent-demo");
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.file(dataDir))
                .install(new ToolsFeature())
                .install(new FileUploadFeature())
                .llmConfigFromClasspath("demo-llm-config.json")
                .agentDefinitionFromClasspath("demo-agent.json");
        builder.feature(CoreFeature.class)
                .llmConfig(LlmConfig.lmStudio("embeddings", "text-embedding-nomic-embed-text-v1.5", "http://localhost:1234"))
                .defaultLlmConfigName("demo-llm");

        try (AgentRuntime runtime = builder.build()) {
            AgentSession session = runtime.openSession("demo-agent", UserId.of("demo-user"));
            String result = runtime.attachFile(session.id(), "company-policy.md",
                    new ByteArrayInputStream(POLICY.getBytes(StandardCharsets.UTF_8)));
            System.out.println("Attach: " + result);

            String question = args.length > 0 ? String.join(" ", args)
                    : "According to the attached policy, when must receipts be uploaded?";
            System.out.println("Q: " + question);
            String answer = runtime.chat(session.id(), question, event -> { });
            System.out.println("A: " + answer);
        }
    }
}
