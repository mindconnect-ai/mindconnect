package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Seeds the three built-in stateless agent definitions on startup if they don't already exist.
 * <p>
 * The three agents are:
 * <ul>
 *   <li>{@code tool-summarizer} — compresses large tool results after they've been sent to the LLM</li>
 *   <li>{@code title-generator} — generates a short conversation title from the first user message</li>
 *   <li>{@code conversation-summarizer} — summarizes older conversation turns for memory compression</li>
 * </ul>
 * Each definition is seeded with a sensible default system prompt and the globally configured
 * fallback {@code llmConfigName}. Once seeded, the definitions can be edited at runtime like any
 * other {@link AgentDefinition} — the seeder will not overwrite existing records.
 */
public class StatelessAgentSeeder {

    private static final Logger log = LoggerFactory.getLogger(StatelessAgentSeeder.class);

    // ── built-in task names ───────────────────────────────────────────────────
    public static final String TOOL_SUMMARIZER        = "tool-summarizer";
    public static final String TITLE_GENERATOR        = "title-generator";
    public static final String CONVERSATION_SUMMARIZER = "conversation-summarizer";

    // ── default system prompts ────────────────────────────────────────────────
    private static final String TOOL_SUMMARIZER_PROMPT =
            "You are a tool result summarizer. Your job is to compress a tool result while preserving every piece of exact data.\n" +
            "Rules:\n" +
            "- NEVER paraphrase, invent, or shorten URLs, file paths, IDs, version numbers, email addresses, or proper nouns — copy them character-for-character.\n" +
            "- Output a compact bullet list of the key facts. Each bullet must quote exact values from the original.\n" +
            "- If a URL, path, or ID appears in the original, it MUST appear unchanged in your output.\n" +
            "- Do not add preamble, commentary, or a closing sentence — bullets only.";

    private static final String TITLE_GENERATOR_PROMPT =
            "You generate short conversation titles. " +
            "Given a conversation exchange, respond with a concise title of 4-6 words that captures the topic. " +
            "No punctuation at the end, no quotes — just the title.";

    private static final String CONVERSATION_SUMMARIZER_PROMPT =
            "You are a conversation summarizer. " +
            "Summarize the given conversation turns into a compact paragraph that captures " +
            "the key topics, decisions, and outcomes. " +
            "Write in third person. No preamble — output only the summary.";

    private record SeedEntry(String name, String description, String systemPrompt) {}

    private static final List<SeedEntry> SEEDS = List.of(
            new SeedEntry(TOOL_SUMMARIZER,
                    "Compresses large tool results after the first LLM turn",
                    TOOL_SUMMARIZER_PROMPT),
            new SeedEntry(TITLE_GENERATOR,
                    "Generates a short title for a conversation from the first user message",
                    TITLE_GENERATOR_PROMPT),
            new SeedEntry(CONVERSATION_SUMMARIZER,
                    "Summarizes older conversation turns for working-memory compression",
                    CONVERSATION_SUMMARIZER_PROMPT)
    );

    // ── fields ────────────────────────────────────────────────────────────────
    private final AgentDefinitionRepository repository;
    private final Namespace namespace;
    private final String defaultLlmConfigName;

    public StatelessAgentSeeder(AgentDefinitionRepository repository,
                                Namespace namespace,
                                String defaultLlmConfigName) {
        this.repository = repository;
        this.namespace = namespace;
        this.defaultLlmConfigName = defaultLlmConfigName;
    }

    /**
     * Seeds missing definitions. Existing records are never overwritten.
     * Call this once on application startup.
     * <p>
     * If {@code defaultLlmConfigName} is {@code null} (property not configured), seeding is
     * skipped entirely — the definitions cannot be created without a valid LLM config.
     */
    public void seed() {
        if (defaultLlmConfigName == null) {
            log.warn("Skipping stateless agent seeding — mindconnect.agent.stateless.llm-config-name not configured");
            return;
        }
        for (SeedEntry entry : SEEDS) {
            repository.findByName(namespace, entry.name()).ifPresentOrElse(
                    existing -> log.debug("Stateless agent '{}' already exists — skipping seed", entry.name()),
                    () -> {
                        AgentDefinition def = AgentDefinition.create(
                                namespace, entry.name(), entry.description(),
                                entry.systemPrompt(), null,
                                defaultLlmConfigName);
                        repository.save(def);
                        log.info("Seeded stateless agent '{}'", entry.name());
                    }
            );
        }
    }
}
