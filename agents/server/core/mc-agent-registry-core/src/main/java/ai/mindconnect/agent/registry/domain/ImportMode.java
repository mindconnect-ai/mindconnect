package ai.mindconnect.agent.registry.domain;

/**
 * What an import does when the installation already has something of that
 * name.
 *
 * <p>The question cannot be answered centrally, because both answers are
 * right: re-importing a registry agent to pick up its new prompt wants
 * {@link #OVERWRITE}, and importing a package whose LLM alias you have
 * carefully pointed at your own model wants {@link #SKIP_EXISTING}. So the
 * person importing says, per import, and the report says what happened.
 */
public enum ImportMode {

    /**
     * Keep what is here. An existing name is reported as
     * {@link ImportStatus#SKIPPED} and the rest of the package still installs
     * — the default, because it cannot destroy anything.
     */
    SKIP_EXISTING,

    /**
     * Replace what is here, keeping the local entity's id and version so that
     * everything pointing at it keeps working.
     */
    OVERWRITE
}
