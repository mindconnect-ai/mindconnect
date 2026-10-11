package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.domain.BaseStepData;

/**
 * A code step that runs in the caller's {@code code_execute} sandbox — a
 * container, locally or in the virtual environment — instead of a script
 * engine inside the server. What a project's workflows use for {@code code}:
 * their code was written by whoever wrote the repository.
 *
 * <p>The workflow's variables go in as the program's top-level variables;
 * the top-level variables the program creates or changes come back as
 * workflow variables, as far as they are JSON. The one called {@code result}
 * is the step's result. Everything crosses as JSON, so a value that is not
 * JSON (a function, a module, a file handle) stays inside.
 *
 * <p>Type discriminator: {@code sandboxcode}.
 */
public class SandboxCodeData extends BaseStepData {

    /** {@code python} or {@code node} — what the sandbox runs. */
    private String language;

    /** The program. */
    private String code;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
