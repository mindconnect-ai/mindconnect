package ai.mindconnect.agent.runtime.api.domain;

/**
 * The model the runtime's own small tasks run on — naming a session,
 * summarising a history — where an assembly names one: such a task needs no
 * strong model, and an agent's own may be the most expensive there is.
 * Provided as a service by whoever assembles the runtime; without it each
 * task runs on the model of the agent it works for.
 */
public record BackgroundModel(ModelSettings settings) {

    public static BackgroundModel named(String model) {
        return new BackgroundModel(ModelSettings.ofModel(model));
    }
}
