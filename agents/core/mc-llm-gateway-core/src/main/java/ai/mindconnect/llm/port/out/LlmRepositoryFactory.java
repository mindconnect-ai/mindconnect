package ai.mindconnect.llm.port.out;

/**
 * Creates the LLM layer's repositories for one persistence backend. One
 * implementation per backend — file, in-memory, Postgres — so that whoever
 * assembles a runtime picks a factory once instead of switching per repository.
 */
public interface LlmRepositoryFactory {

    LlmConfigRepository llmConfigRepository();
}
