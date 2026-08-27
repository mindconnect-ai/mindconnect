package ai.mindconnect.workflow.domain;

import ai.mindconnect.schema.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level workflow definition.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowData extends BaseStepContainerData {

    private String uid;

    /**
     * The workflow's declared inputs, as an object {@link Schema} — each
     * property is a parameter, with its type, and {@code required} names the
     * mandatory ones. One type covers a plain name list, a typed field and a
     * nested structure, and it renders straight to a form.
     *
     * <p>Older files stored this as a bare list of names; that still reads (see
     * the Jackson module in {@code mc-workflow-jackson}), as string properties.
     */
    private Schema params = Schema.object();

    /**
     * @deprecated Defaults live on each parameter's {@link Schema#getDefaultValue()}
     *             now. Kept so pre-existing files carrying a {@code paramDefaults}
     *             map still deserialise; not written for new workflows.
     */
    @Deprecated
    private Map<String, String> paramDefaults = new LinkedHashMap<>();

    /** Embedded sub-workflow definitions (looked up by name before the registry). */
    private List<WorkflowData> subWorkflows = new ArrayList<>();

    /** Free-form tag to distinguish workflow categories (e.g. "import", "query"). */
    private String workflowType;

    /**
     * Convenience: declare simple string params by name (tests, builders).
     * Deliberately not named {@code setParams} — that would give Jackson a
     * second setter for the {@code params} property (a {@code String[]}) next to
     * the typed {@link Schema} one, and it would pick the wrong one.
     */
    public void declareParams(String... names) {
        this.params = Schema.object();
        for (String name : names) {
            this.params.prop(name, Schema.string());
        }
    }

    public void addParam(String name) {
        this.params.prop(name, Schema.string());
    }

    /** The declared parameter names, in order. */
    public List<String> paramNames() {
        return new ArrayList<>(params.getProperties().keySet());
    }
}
