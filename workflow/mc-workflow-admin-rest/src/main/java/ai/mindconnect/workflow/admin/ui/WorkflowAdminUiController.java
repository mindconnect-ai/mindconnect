package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.schema.Schema;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.ui.model.UiTree;
import ai.mindconnect.ui.model.UiTreeNode;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.workflow.admin.run.RunStore;
import ai.mindconnect.workflow.admin.run.WorkflowRunService;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.admin.ui.ParamCoercion;
import ai.mindconnect.workflow.admin.ui.ParamFormFields;
import ai.mindconnect.workflow.admin.ui.StepFormBuilder;
import ai.mindconnect.workflow.admin.ui.StepJsonMapper;
import ai.mindconnect.workflow.admin.ui.StepMapper;
import ai.mindconnect.workflow.admin.ui.WorkflowUiBuilder;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.edit.StepNames;
import ai.mindconnect.workflow.edit.WorkflowMutator;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.ui.diagram.WorkflowDiagramBuilder;
import ai.mindconnect.workflow.step.form.FormStepData;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshots;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST + semantic-ui front end for editing and running workflows. Returns
 * {@link UiPage} trees; the SUI message converter renders them as SSR HTML or
 * SPA JSON per the request's Accept header.
 *
 * <p>Base path is {@code /workflow-admin}, matching the URLs the
 * {@link WorkflowUiBuilder} emits.
 */
@RestController
@RequestMapping(WorkflowAdminUiController.BASE)
public class WorkflowAdminUiController {

    /** Base path for both the data API and the bookmarkable user URLs. The
     *  controller returns JSON UiPages here; a host app's SPA controller serves
     *  the shell on the same paths for {@code Accept: text/html} navigations. */
    public static final String BASE = "/workflow-admin";

    /** Body-level host the renderers paint open dialogs into. */
    private static final String DIALOG_HOST = "sui-dialogs";

    /** Id of the admin's single dialog. The admin never stacks modals, so one
     *  id is enough — and it lets any dialog-opening patch first REMOVE the
     *  previous dialog (a no-op when none is open) before APPENDing the new
     *  one, which is how "details → edit" replaces the modal in place. */
    private static final String DIALOG_ID = "wf-dialog";

    /** Form field that carries the "persist this run" choice, kept out of params. */
    private static final String PERSIST_FIELD = "__persist";

    private final ai.mindconnect.workflow.admin.service.WorkflowAdminService service;
    private final RunStore runStore = new RunStore();
    private final StepFormBuilder formBuilder = new StepFormBuilder();
    /** Shared with the diagram editor: one name-addressed mutation layer. */
    private final WorkflowMutator mutator = new WorkflowMutator();

    /**
     * Base directory the run form's file chooser browses — by convention the
     * same directory the agent tools operate in ({@code mindconnect.tools.base-dir});
     * override with {@code mindconnect.workflow-admin.browse-dir}. Falls back to
     * the user's home.
     */
    private final java.nio.file.Path browseBase;

    /** Serialises UiPatch frames for the SSE run stream. */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Runs streamed workflow executions off the request thread. */
    private final java.util.concurrent.ExecutorService runStreamExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowAdminUiController(ai.mindconnect.workflow.admin.service.WorkflowAdminService service,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${mindconnect.workflow-admin.browse-dir:${mindconnect.tools.base-dir:}}")
                                   String browseDir) {
        this.service = service;
        this.objectMapper = objectMapper;
        String dir = browseDir == null || browseDir.isBlank() ? System.getProperty("user.home") : browseDir;
        this.browseBase = java.nio.file.Path.of(dir).toAbsolutePath().normalize();
    }

    // -----------------------------------------------------------------------
    // Workflow list & view
    // -----------------------------------------------------------------------

    @GetMapping
    public UiPage list(@RequestParam(required = false) String q) {
        UiTable table = UiTable.of("wf-table", "Workflows")
                .icon("branch")
                .action(UiAction.primary("new", "New Workflow").icon("add")
                        .dispatch("GET", BASE + "/new"))
                // The name IS the way in (same convention as the agent list):
                // a link cell replaces the old "Open" row-action button, and
                // the redundant Name column (identical to the id) is gone.
                .column(UiColumn.of("id", "Workflow").asSortable()
                        .withCellTemplate(UiLink.of("wf-open", BASE + "/{id}", "{id}")))
                .column(UiColumn.number("steps", "Steps"))
                .rowAction(UiAction.primary("run", "Run").icon("flash")
                        .onClick(UiTrigger.go(BASE + "/{id}/run")))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this workflow?")
                        .dispatch("POST", BASE + "/{id}/delete"));

        for (var info : service.list(q)) {
            table.row(Map.of("id", info.id(), "name", info.name(), "steps", info.steps()));
        }

        String searchFormId = "wf-search";
        UiForm search = UiForm.of(searchFormId, null);
        search.field(UiField.text("q", "", q)
                .asEditable()
                .icon("search")
                .placeholder("Search id or name…")
                .onChange(UiTrigger.api("POST", BASE + "/search", searchFormId)));
        table.headerExtra(search);

        return UiPage.of(BASE, table);
    }

    /** The search field posts its form here; the response is the filtered list. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> form) {
        Object q = form.get("q");
        return list(q == null ? null : String.valueOf(q));
    }

    /** Workflow ids double as file names and URL segments, so keep them tame. */
    private static final Pattern WORKFLOW_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Path segments the controller claims for itself — a workflow must not shadow them. */
    private static final java.util.Set<String> RESERVED_NAMES =
            java.util.Set.of("new", "create", "search", "close-dialog");

    @GetMapping("/new")
    public UiPatch newWorkflowDialog() {
        return openDialog("New workflow", newWorkflowForm(null));
    }

    /**
     * Creates an empty workflow and opens its editor. Success returns the
     * editor {@link UiPage} (a full page render clears the open dialog);
     * a rejected name returns the dialog again as a {@link UiPatch} with an
     * error toast, keeping the user's input.
     */
    @PostMapping("/create")
    public Object createWorkflow(@RequestBody Map<String, Object> form) {
        String name = String.valueOf(form.getOrDefault("name", "")).trim();
        String rejection = null;
        if (name.isBlank()) {
            rejection = "The workflow needs a name.";
        } else if (!WORKFLOW_NAME.matcher(name).matches()) {
            rejection = "Use letters, digits, dot, dash or underscore (starting with a letter or digit).";
        } else if (RESERVED_NAMES.contains(name)) {
            rejection = "'" + name + "' is a reserved name.";
        } else if (service.exists(name)) {
            rejection = "A workflow named '" + name + "' already exists.";
        }
        if (rejection != null) {
            return openDialog("New workflow", newWorkflowForm(name))
                    .toast(UiToast.error(rejection).title("Cannot create workflow"));
        }
        WorkflowData wf = new WorkflowData();
        wf.setName(name);
        service.save(name, wf);
        return view(name);
    }

    private UiForm newWorkflowForm(String name) {
        String formId = "wf-new-form";
        UiForm form = UiForm.of(formId, null);
        form.field(UiField.text("name", "Name", name)
                .asEditable().asRequired()
                .hint("Letters, digits, dot, dash, underscore — the name is also the file and URL id"));
        form.action(UiAction.primary("create", "Create").icon("add").dispatch("POST", BASE + "/create", formId));
        form.action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("POST", BASE + "/close-dialog"));
        return form;
    }

    @PostMapping("/{wf}/delete")
    public UiPage deleteWorkflow(@PathVariable String wf) {
        service.delete(wf);
        return list(null);
    }

    @GetMapping("/{wf}")
    public UiPage view(@PathVariable String wf) {
        WorkflowData data = require(wf);
        // The same header bar every other screen carries: icon, the thing's
        // own name as the title (the icon says it is a workflow — a
        // "Workflow:" prefix would say it twice), actions on the right. A
        // header-only UiList renders the identical bar to the one on Agents.
        UiList header = UiList.of("wf-view:" + wf + ":header", wf).icon("workflow");
        header.action(UiAction.secondary("back", "All workflows").icon("back")
                .onClick(UiTrigger.go(BASE)));
        header.action(UiAction.primary("run", "Run").icon("flash")
                .onClick(UiTrigger.go(BASE + "/" + wf + "/run")));

        UiStack page = UiStack.of("wf-view:" + wf).child(header).child(tabsFor(wf, data, "editor"));
        return UiPage.of(BASE + "/" + wf, page);
    }

    /** The workflow detail tabs; {@code initialSection} picks the tab shown after a re-render. */
    private UiSection tabsFor(String wf, WorkflowData data, String initialSection) {
        List<WorkflowInstanceSnapshot> runs = service.instancesOf(wf);
        String runsLabel = runs.isEmpty() ? "Runs" : "Runs (" + runs.size() + ")";
        int paramCount = data.getParams() == null || data.getParams().getProperties() == null
                ? 0 : data.getParams().getProperties().size();
        String paramsLabel = paramCount == 0 ? "Parameters" : "Parameters (" + paramCount + ")";
        UiSection tabs = new UiSection();
        tabs.setId("wf-tabs:" + wf);
        tabs.section("editor", "Editor", new WorkflowUiBuilder(wf).render(data));
        tabs.section("params", paramsLabel, paramsTab(wf, data));
        tabs.section("diagram", "Diagram", new WorkflowDiagramBuilder().build(data));
        tabs.section("runs", runsLabel, runsTab(wf, runs));
        tabs.initialSection(initialSection);
        return tabs;
    }

    /**
     * The workflow's run history: every kept instance, newest first. The
     * instance IS the record — a halted one can be resumed, any one can be
     * reopened to read its trace, or deleted. Instances land here when the user
     * ticked "keep", and always when they halted.
     */
    private UiNode runsTab(String wf, List<WorkflowInstanceSnapshot> runs) {
        UiStack box = UiStack.of("wf-runs:" + wf).gap(6);
        if (runs.isEmpty()) {
            box.child(UiText.of("wf-runs:" + wf + ":empty",
                    "No kept runs yet. Tick \"keep this run\" when running, or run a workflow "
                    + "that halts — those are always kept.").withCssClass("wf-run-meta"));
            return box;
        }
        for (WorkflowInstanceSnapshot r : runs) {
            box.child(runRow(wf, r));
        }
        return box;
    }

    private static WorkflowInstanceSnapshot.Status statusOf(WorkflowInstanceSnapshot r) {
        return ai.mindconnect.workflow.admin.service.WorkflowAdminService.statusOf(r);
    }

    private UiNode runRow(String wf, WorkflowInstanceSnapshot r) {
        String id = "wf-run:" + wf + ":" + r.getInstanceId();
        WorkflowInstanceSnapshot.Status status = statusOf(r);
        String state = switch (status) {
            case FINISHED -> "✓ success";
            case HALTED -> "⏸ halted";
            case ERROR -> "✗ failed";
        };
        String css = switch (status) {
            case FINISHED -> "success";
            case HALTED -> "halted";
            case ERROR -> "error";
        };
        long when = r.getStartedAt() > 0 ? r.getStartedAt() : r.getSuspendedAt();

        UiStack row = UiStack.of(id).direction(UiStack.Direction.HORIZONTAL).gap(12)
                .child(UiText.of(id + ":when", stamp(when)).withCssClass("wf-run-meta"))
                .child(UiText.of(id + ":state", state)
                        .withCssClass("wf-run-state wf-run-state--" + css))
                .child(UiAction.secondary(id + ":view", "View")
                        .onClick(UiTrigger.api("GET", BASE + "/" + wf + "/instance/"
                                + enc(r.getInstanceId()) + "/view")));
        if (status == WorkflowInstanceSnapshot.Status.HALTED) {
            row.child(UiAction.primary(id + ":resume", "Resume")
                    .dispatch("POST", BASE + "/" + wf + "/instance/"
                            + enc(r.getInstanceId()) + "/continue"));
        }
        row.child(UiAction.danger(id + ":delete", "Delete")
                .confirm(status == WorkflowInstanceSnapshot.Status.HALTED
                        ? "Delete this run? A halted run can no longer be resumed once deleted."
                        : "Delete this run from history?")
                .dispatch("POST", BASE + "/" + wf + "/instance/" + enc(r.getInstanceId()) + "/delete"));
        row.withCssClass("wf-run-step");
        return row;
    }

    /** Reopen a kept run — renders the trace stored on the instance exactly as when it ran. */
    @GetMapping("/{wf}/instance/{id}/view")
    public UiPage viewInstance(@PathVariable String wf, @PathVariable String id) {
        return renderRun(wf, WorkflowRunService.traceReport(requireInstance(id)));
    }

    /** Delete a kept run — history and, when it was still halted, the resume state with it. */
    @PostMapping("/{wf}/instance/{id}/delete")
    public UiPage deleteInstance(@PathVariable String wf, @PathVariable String id) {
        service.deleteInstance(id);
        return view(wf);
    }

    // -----------------------------------------------------------------------
    // Step details & edit
    // -----------------------------------------------------------------------

    @GetMapping("/{wf}/step/{ref}")
    public UiPatch stepDetails(@PathVariable String wf, @PathVariable String ref) {
        WorkflowData data = require(wf);
        StepData step = requireStep(data, ref);
        UiDetail detail = UiDetail.of("step-detail", step.getType() + " step")
                .field(UiField.text("name", "Name", step.getName()))
                .field(UiField.text("type", "Type", step.getType()))
                .field(UiField.text("assignResultToVar", "Assign result to var",
                        step.getAssignResultToVar()));
        UiStack body = UiStack.of("step-detail-page")
                .child(detail)
                .child(UiAction.primary("edit", "Edit").icon("edit")
                        .onClick(UiTrigger.api("GET", stepUrl(wf, ref, "edit"))));
        // Open as a modal dialog over the current list — no page navigation.
        return openDialog(step.getType() + " step", body);
    }

    @GetMapping("/{wf}/step/{ref}/edit")
    public UiPatch stepEdit(@PathVariable String wf, @PathVariable String ref) {
        WorkflowData data = require(wf);
        StepData step = requireStep(data, ref);
        UiForm form = formBuilder.build(step, stepUrl(wf, ref, "save"), stepUrl(wf, ref, "json"));
        return openDialog("Edit " + step.getType() + " step", form);
    }

    // -----------------------------------------------------------------------
    // Raw JSON editing — the escape hatch for anything the typed form omits
    // -----------------------------------------------------------------------

    @GetMapping("/{wf}/step/{ref}/json")
    public UiPatch stepJson(@PathVariable String wf, @PathVariable String ref) {
        WorkflowData data = require(wf);
        StepData step = requireStep(data, ref);
        return openJsonDialog(wf, ref, step, StepJsonMapper.toJson(step));
    }

    /**
     * Replaces the step named {@code ref} with the submitted JSON. The step is
     * swapped wholesale rather than field-patched, so the JSON may change the
     * step's type (via {@code @class}) and its children.
     */
    @PostMapping("/{wf}/step/{ref}/json")
    public UiPatch stepJsonSave(@PathVariable String wf, @PathVariable String ref,
                                @RequestBody Map<String, Object> form) {
        WorkflowData data = require(wf);
        WorkflowMutator.StepLocation loc = requireLocation(data, ref);
        String json = String.valueOf(form.get("json"));

        StepData replacement;
        try {
            replacement = StepJsonMapper.fromJson(json);
        } catch (StepJsonMapper.InvalidStepJsonException ex) {
            // Keep the dialog open with the user's text intact — losing a hand-
            // written step to a stray comma would be a poor trade for a 500.
            return openJsonDialog(wf, ref, loc.step(), json)
                    .toast(UiToast.error(ex.getMessage()).title("Invalid step JSON"));
        }

        loc.container().set(loc.index(), replacement);
        return saveAndRefresh(wf, data, UiToast.success("Step saved."));
    }

    private UiPatch openJsonDialog(String wf, String ref, StepData step, String json) {
        UiForm form = formBuilder.buildJson(
                step, json, stepUrl(wf, ref, "json"), stepUrl(wf, ref, "edit"));
        return openDialog(step.getType() + " step — JSON", form);
    }

    @PostMapping("/{wf}/step/{ref}/save")
    public UiPatch stepSave(@PathVariable String wf, @PathVariable String ref,
                            @RequestBody Map<String, Object> form) {
        WorkflowData data = require(wf);
        StepData step = requireStep(data, ref);

        // A rename changes the step's address, so it has to be a legal, free
        // name — jumpTo/resultFrom resolve by name too, and a duplicate would
        // silently bind to whichever step came first.
        Object submitted = form.get("name");
        if (submitted != null) {
            String newName = String.valueOf(submitted).trim();
            String rejection = rejectName(data, ref, newName);
            if (rejection != null) {
                return openDialog("Edit " + step.getType() + " step",
                        formBuilder.build(step, stepUrl(wf, ref, "save"), stepUrl(wf, ref, "json")))
                        .toast(UiToast.error(rejection).title("Cannot rename step"));
            }
        }

        StepMapper.apply(step, form);
        return saveAndRefresh(wf, data, UiToast.success("Step saved."));
    }

    @PostMapping("/{wf}/step/{ref}/delete")
    public UiPatch stepDelete(@PathVariable String wf, @PathVariable String ref) {
        WorkflowData data = require(wf);
        if (!mutator.delete(data, ref)) {
            throw new NoSuchStepException(ref);
        }
        return saveAndRefresh(wf, data, null);
    }

    // -----------------------------------------------------------------------
    // Add step
    // -----------------------------------------------------------------------

    /**
     * @param container {@link WorkflowUiBuilder#ROOT}, or the mutator's container
     *                  DSL ({@code block:<name>}, {@code foreach:<name>},
     *                  {@code if:<name>:then:<i>}, {@code if:<name>:else})
     */
    @GetMapping("/{wf}/add/{container}")
    public UiPatch addPicker(@PathVariable String wf, @PathVariable String container) {
        UiForm form = UiForm.of("add-step", "Add step")
                .field(UiField.select("type", "Step type", "assignvariables",
                        StepMapper.typeOptions()).asEditable().asRequired())
                .field(UiField.text("name", "Name", null).asEditable()
                        .hint("Optional — a unique name is generated if you leave this empty."))
                .action(UiAction.primary("create", "Create").icon("add")
                        .dispatch("POST", addUrl(wf, container), "add-step"))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .onClick(UiTrigger.api("POST", BASE + "/" + wf + "/close-dialog")));
        return openDialog("Add step", form);
    }

    @PostMapping("/{wf}/add/{container}")
    public UiPatch addStep(@PathVariable String wf, @PathVariable String container,
                           @RequestBody Map<String, Object> form) {
        WorkflowData data = require(wf);
        String type = String.valueOf(form.get("type"));
        StepData step = StepMapper.create(type);

        Object submitted = form.get("name");
        String wanted = submitted == null ? "" : String.valueOf(submitted).trim();
        if (!wanted.isEmpty()) {
            String rejection = rejectName(data, null, wanted);
            if (rejection != null) {
                return addPicker(wf, container)
                        .toast(UiToast.error(rejection).title("Cannot name step"));
            }
            step.setName(wanted);
        } else {
            // The name is the step's address, so it cannot be left empty. The
            // engine would fill it with a UUID; a user needs something readable.
            step.setName(StepNames.uniqueName(data, type));
        }

        if (WorkflowUiBuilder.ROOT.equals(container)) {
            data.getSteps().add(step);
        } else {
            // Creates a missing then/else block on the way in, which is what
            // makes an if with empty branches fillable.
            mutator.insertIntoContainer(data, container, WorkflowMutator.Position.LAST, step);
        }
        return saveAndRefresh(wf, data, UiToast.success("Step added."));
    }

    /** Dismisses any open dialog and used by cancel buttons. */
    @PostMapping({"/close-dialog", "/{wf}/close-dialog"})
    public UiPatch closeDialog() {
        return UiPatch.of().patch(UiPatch.Operation.remove(DIALOG_ID));
    }

    /** Patch that closes the open dialog and re-renders the step list in place. */
    private UiPatch closeDialogAndRefresh(String wf, WorkflowData data) {
        return closeDialog()
                .patch(UiPatch.Operation.replace(
                        "wf-body:" + wf, new WorkflowUiBuilder(wf).render(data)));
    }

    /**
     * Patch that shows {@code body} as the admin's modal: drop whatever dialog
     * is currently open, then mount the new one into the dialog host. The
     * leading REMOVE is what makes "details → edit" swap the modal instead of
     * stacking a second one on top; it is a no-op when nothing is open.
     */
    private UiPatch openDialog(String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(DIALOG_ID))
                .patch(UiPatch.Operation.append(DIALOG_HOST, dialog));
    }

    // -----------------------------------------------------------------------
    // Run
    // -----------------------------------------------------------------------

    @GetMapping("/{wf}/run")
    public UiPage runForm(@PathVariable String wf) {
        WorkflowData data = require(wf);
        UiForm form = UiForm.of("run-form", "Run " + wf);
        // Prefilled with the last run's inputs; path params carry a Browse… button.
        ParamFormFields.addTo(form, data.getParams(),
                name -> BASE + "/" + wf + "/browse/" + enc(name),
                lastParams(wf, data));
        // Off by default: a run is kept in history only when asked. A run that
        // halts is kept regardless — it has to be, to be resumable.
        form.field(UiField.bool(PERSIST_FIELD, "Keep this run in history", false).asEditable()
                .hint("A run that halts is always kept, so it can be resumed."));
        // Streaming run in two hops: this POST answers with the live-run page
        // (progress container already in the DOM, an activeStreams entry naming
        // the SSE resume URL); the event bus then opens that GET stream, which
        // starts the execution and patches per-step progress into the page.
        form.action(UiAction.primary("run", "Run").icon("flash")
                .onClick(UiTrigger.api("POST", BASE + "/" + wf + "/run/start", "run-form")));
        form.action(UiAction.secondary("back", "Back").icon("back")
                .onClick(UiTrigger.go(BASE + "/" + wf)));
        return UiPage.of(BASE + "/" + wf + "/run", form);
    }

    /** Last submitted run inputs per workflow — this app run only; kept runs fill the gap after a restart. */
    private final Map<String, Map<String, String>> lastRunInputs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The values to prefill the run form with: what the user submitted last
     * (this app run), else the newest kept run's scope values for the declared
     * params.
     */
    private Map<String, String> lastParams(String wf, WorkflowData data) {
        Map<String, String> remembered = lastRunInputs.get(wf);
        if (remembered != null) {
            return remembered;
        }
        Schema params = data.getParams();
        if (params == null || params.getProperties() == null || params.getProperties().isEmpty()) {
            return Map.of();
        }
        Map<String, String> fromRecord = new LinkedHashMap<>();
        List<WorkflowInstanceSnapshot> records = service.instancesOf(wf);
        if (!records.isEmpty()) {
            WorkflowRunService.RunReport report = WorkflowRunService.traceReport(records.get(0));
            if (report.scope() != null && report.scope().variables() != null) {
                for (WorkflowRunService.VarSnapshot var : report.scope().variables()) {
                    if (params.getProperties().containsKey(var.name()) && var.value() != null) {
                        fromRecord.put(var.name(), var.value());
                    }
                }
            }
        }
        return fromRecord;
    }

    // -----------------------------------------------------------------------
    // Server-side file chooser for path-typed run params
    // -----------------------------------------------------------------------

    /** Browse dialog: directories navigate, files pick, the current directory is pickable too. */
    @GetMapping("/{wf}/browse/{param}")
    public UiPatch browse(@PathVariable String wf, @PathVariable String param,
                          @RequestParam(required = false) String dir) throws java.io.IOException {
        require(wf);
        java.nio.file.Path current = resolveBrowseDir(dir);
        String rel = relativize(current);

        UiList list = UiList.of("browse-list", rel.isEmpty() ? "(base directory)" : rel);
        if (!current.equals(browseBase)) {
            String parent = relativize(current.getParent());
            list.item(UiList.Item.of("up", "⬆️  ..")
                    .onClick(UiTrigger.api("GET", browseUrl(wf, param, parent))));
        }
        List<java.nio.file.Path> entries;
        try (var stream = java.nio.file.Files.list(current)) {
            entries = stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(java.util.Comparator
                            .comparing((java.nio.file.Path p) -> !java.nio.file.Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        }
        for (java.nio.file.Path entry : entries) {
            String name = entry.getFileName().toString();
            String entryRel = relativize(entry);
            boolean isDir = java.nio.file.Files.isDirectory(entry);
            list.item(UiList.Item.of("e-" + name, (isDir ? "📁 " : "📄 ") + name)
                    .onClick(isDir
                            ? UiTrigger.api("GET", browseUrl(wf, param, entryRel))
                            : UiTrigger.api("GET", pickUrl(wf, param, entryRel))));
        }

        UiStack body = UiStack.of("browse-body").gap(8)
                .child(list)
                .child(UiAction.primary("pick-dir", "Use this directory").icon("check")
                        .dispatch("GET", pickUrl(wf, param, rel)))
                .child(UiAction.secondary("cancel-browse", "Cancel").icon("cancel")
                        .dispatch("POST", BASE + "/close-dialog"));
        return openDialog("Choose — " + param, body);
    }

    /** Closes the chooser and re-renders the run form's field with the picked path. */
    @GetMapping("/{wf}/browse/{param}/pick")
    public UiPatch pick(@PathVariable String wf, @PathVariable String param,
                        @RequestParam String path) {
        WorkflowData data = require(wf);
        Schema prop = requireParam(data, param);
        // Round-trip through the resolver to reject anything outside the base.
        String rel = relativize(resolveBrowseDirOrFile(path));
        UiField field = ParamFormFields.field(param, prop, data.getParams().isRequired(param), rel,
                BASE + "/" + wf + "/browse/" + enc(param));
        return closeDialog().patch(UiPatch.Operation.replace(param, field));
    }

    private String browseUrl(String wf, String param, String dir) {
        String url = BASE + "/" + wf + "/browse/" + enc(param);
        return dir == null || dir.isEmpty()
                ? url
                : url + "?dir=" + UriUtils.encodeQueryParam(dir, StandardCharsets.UTF_8);
    }

    private String pickUrl(String wf, String param, String path) {
        return BASE + "/" + wf + "/browse/" + enc(param) + "/pick?path="
                + UriUtils.encodeQueryParam(path == null ? "" : path, StandardCharsets.UTF_8);
    }

    /** Resolves a relative directory inside the browse base; anything else lands back at the base. */
    private java.nio.file.Path resolveBrowseDir(String dir) {
        java.nio.file.Path resolved = resolveBrowseDirOrFile(dir);
        return java.nio.file.Files.isDirectory(resolved) ? resolved : browseBase;
    }

    private java.nio.file.Path resolveBrowseDirOrFile(String rel) {
        if (rel == null || rel.isBlank()) {
            return browseBase;
        }
        java.nio.file.Path resolved = browseBase.resolve(rel).normalize();
        return resolved.startsWith(browseBase) ? resolved : browseBase;
    }

    private String relativize(java.nio.file.Path path) {
        return browseBase.relativize(path.toAbsolutePath().normalize()).toString();
    }

    /**
     * Streaming run, hop 1: remember the inputs, park the coerced params as a
     * pending {@link LiveRun}, and answer with the live-run <em>page</em>. The
     * page carries the progress container ({@code wf-run-live}) plus an
     * {@code activeStreams} entry — the event bus renders the page first and
     * then opens the GET stream, so every patch finds its target already in
     * the DOM. The classic POST {@code /{wf}/run} stays for non-streaming
     * callers.
     */
    @PostMapping("/{wf}/run/start")
    public UiPage startRun(@PathVariable String wf, @RequestBody Map<String, Object> params) {
        WorkflowData data = require(wf);
        boolean persist = Boolean.parseBoolean(String.valueOf(params.remove(PERSIST_FIELD)));
        Map<String, String> remembered = new LinkedHashMap<>();
        params.forEach((k, v) -> { if (v != null) remembered.put(k, String.valueOf(v)); });
        lastRunInputs.put(wf, remembered);
        Map<String, Object> typed = ParamCoercion.coerce(data.getParams(), params);

        String runId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        liveRuns.put(runId, new LiveRun(wf, data, typed, persist, null, "wf-run-live-" + runId));
        evictFinishedLiveRuns();
        return liveRunPage(wf, runId, liveRuns.get(runId));
    }

    /**
     * F5 / bookmark target for a streamed run: while the run is pending or
     * executing this re-renders the live page (with the resume stream, so a
     * reloaded tab re-attaches); once finished it renders the full run view.
     * Unknown ids (app restart, evicted) fall back to the run form.
     */
    @GetMapping("/{wf}/run/live/{runId}")
    public UiPage liveRun(@PathVariable String wf, @PathVariable String runId) {
        LiveRun run = liveRuns.get(runId);
        if (run == null) {
            return runForm(wf);
        }
        if (run.report != null) {
            return renderRun(wf, run.report);
        }
        return liveRunPage(wf, runId, run);
    }

    /**
     * Streaming run, hop 2: the SSE connection the live-run page's
     * {@code activeStreams} entry points at. The first connect starts the
     * execution; later connects (F5, second tab) just re-attach — the view
     * replays its current state as the first frame, so the client is complete
     * from the first byte. {@code lastSeq} is accepted for protocol
     * compatibility but ignored: every frame replaces the whole panel, so
     * there is nothing incremental to miss.
     */
    @GetMapping(value = "/{wf}/run/live/{runId}/stream",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>
            liveRunStream(@PathVariable String wf, @PathVariable String runId,
                          @RequestParam(required = false) Long lastSeq) {
        LiveRun run = liveRuns.get(runId);
        if (run == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        run.view.attach(emitter);

        if (run.report != null || run.failure != null) {
            // Finished between page render and connect: replay the outcome, close.
            if (run.report != null) {
                run.view.finish(renderRun(wf, run.report).getNode());
            } else {
                run.view.fail(run.failure);
            }
            run.view.complete();
        } else if (run.started.compareAndSet(false, true)) {
            runStreamExecutor.submit(() -> {
                try {
                    WorkflowRunService.RunReport report;
                    if (run.snapshot != null) {
                        report = service.resume(run.data, run.snapshot, run.typed, run.view);
                    } else {
                        report = service.run(run.data, run.typed, run.view, run.persist);
                    }
                    run.report = report;
                    run.view.finish(renderRun(wf, report).getNode());
                } catch (Exception e) {
                    run.failure = e;
                    run.view.fail(e);
                } finally {
                    run.view.complete();
                }
            });
        }
        return org.springframework.http.ResponseEntity.ok().body(emitter);
    }

    /** In-flight and recently finished streamed runs, keyed by generated run id. */
    private final Map<String, LiveRun> liveRuns = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One streamed run's lifecycle: parked by {@code /run/start}, started by
     * the first stream connect, finished with the report kept so F5 on the
     * live URL still renders the outcome.
     */
    private final class LiveRun {
        final WorkflowData data;
        final Map<String, Object> typed;
        final boolean persist;
        final LiveRunView view;
        /** Non-null when this live run RESUMES a suspended instance. */
        final WorkflowInstanceSnapshot snapshot;
        final java.util.concurrent.atomic.AtomicBoolean started =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile WorkflowRunService.RunReport report;
        volatile Exception failure;

        LiveRun(String wf, WorkflowData data, Map<String, Object> typed, boolean persist) {
            this(wf, data, typed, persist, null);
        }

        LiveRun(String wf, WorkflowData data, Map<String, Object> typed, boolean persist,
                WorkflowInstanceSnapshot snapshot) {
            this(wf, data, typed, persist, snapshot, "wf-run-live");
        }

        LiveRun(String wf, WorkflowData data, Map<String, Object> typed, boolean persist,
                WorkflowInstanceSnapshot snapshot, String channelId) {
            this.data = data;
            this.typed = typed;
            this.persist = persist;
            this.snapshot = snapshot;
            this.view = new LiveRunView(wf, channelId);
        }
    }

    /** The live-run page: current progress panel + the resume-stream entry. */
    private UiPage liveRunPage(String wf, String runId, LiveRun run) {
        String href = BASE + "/" + wf + "/run/live/" + runId;
        UiPage page = UiPage.of(href, run.view.progressNode());
        page.setActiveStreams(List.of(UiPage.ActiveStream.of(
                run.view.channelId, href + "/stream", "Workflow " + wf, href)));
        return page;
    }

    /** Bounded memory: once the map grows, drop everything already finished. */
    private void evictFinishedLiveRuns() {
        if (liveRuns.size() > 50) {
            liveRuns.entrySet().removeIf(e ->
                    e.getValue().report != null || e.getValue().failure != null);
        }
    }

    /**
     * The live progress view pushed over the run stream: one row per executed
     * leaf step (containers like foreach/if provide structure, not work, and
     * would sit "running" for the whole loop). Each engine event re-renders
     * the panel and replaces it in place.
     */
    private final class LiveRunView implements ai.mindconnect.workflow.execution.WorkflowEventListener {

        /**
         * Channel/node id, unique per live run: the SPA's event bus keeps one
         * handle per channel and skips reconnecting a channel it has already
         * seen — a fixed id would silently break streaming for every run
         * after the first (e.g. a resume after a halt).
         */
        final String channelId;

        private final class Row {
            final Object key;
            final String label;
            String state = "running";
            final long startMs = System.currentTimeMillis();
            long endMs;
            String error;

            Row(Object key, String label) {
                this.key = key;
                this.label = label;
            }
        }

        private final String wf;
        /** The currently attached client, if any — replaced on reconnect (F5). */
        private org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter;
        private final List<Row> rows = new ArrayList<>();

        LiveRunView(String wf, String channelId) {
            this.wf = wf;
            this.channelId = channelId;
        }

        /** Points live patches at (a new) client and replays the current state. */
        synchronized void attach(org.springframework.web.servlet.mvc.method.annotation.SseEmitter next) {
            var previous = this.emitter;
            this.emitter = next;
            if (previous != null && previous != next) {
                try { previous.complete(); } catch (Exception ignored) { }
            }
            push();
        }

        /** Closes the attached client's stream, if one is still connected. */
        synchronized void complete() {
            if (emitter != null) {
                try { emitter.complete(); } catch (Exception ignored) { }
            }
        }

        @Override
        public synchronized void beforeStepExecute(ai.mindconnect.workflow.execution.StepInstance<?> step) {
            if (step instanceof ai.mindconnect.workflow.execution.StepContainerInstance) return;
            rows.add(new Row(step, label(step)));
            push();
        }

        @Override
        public synchronized void afterStepExecute(ai.mindconnect.workflow.execution.StepInstance<?> step) {
            closeRow(step, "done", null);
        }

        @Override
        public synchronized void onStepExecuteError(ai.mindconnect.workflow.execution.StepInstance<?> step, Exception e) {
            closeRow(step, "error", e.getMessage());
        }

        synchronized void finish(UiNode finalView) {
            sendPatch(UiPatch.of().patch(UiPatch.Operation.replace(channelId, finalView)));
        }

        synchronized void fail(Exception e) {
            sendPatch(UiPatch.of()
                    .patch(UiPatch.Operation.replace(channelId, progressNode()))
                    .toast(UiToast.error(String.valueOf(e.getMessage())).title("Workflow run failed")));
        }

        private void closeRow(Object key, String state, String error) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                Row row = rows.get(i);
                if (row.key == key && "running".equals(row.state)) {
                    row.state = state;
                    row.endMs = System.currentTimeMillis();
                    row.error = error;
                    break;
                }
            }
            push();
        }

        private static String label(ai.mindconnect.workflow.execution.StepInstance<?> step) {
            var cfg = step.getConfig();
            String name = cfg.getName() != null ? cfg.getName() : "(unnamed)";
            return name + "  ·  " + cfg.getType();
        }

        private void push() {
            sendPatch(UiPatch.of().patch(UiPatch.Operation.replace(channelId, progressNode())));
        }

        private UiNode progressNode() {
            UiStack panel = UiStack.of(channelId).gap(6);
            panel.child(UiText.of(channelId + ":title", "Running " + wf + "…").withCssClass("wf-run-title"));
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                String icon = switch (row.state) {
                    case "done" -> "✅";
                    case "error" -> "❌";
                    default -> "⏳";
                };
                String duration = row.endMs > 0 ? "  (" + (row.endMs - row.startMs) + " ms)" : "";
                String suffix = row.error != null ? "  — " + row.error : "";
                panel.child(UiText.of(channelId + ":row:" + i,
                        icon + "  " + row.label + duration + suffix));
            }
            if (rows.isEmpty()) {
                panel.child(UiText.of(channelId + ":empty", "Starting…"));
            }
            return panel;
        }

        private void sendPatch(UiPatch patch) {
            if (emitter == null) {
                return;     // no client yet — state accumulates in the rows
            }
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("patch")
                        .data(objectMapper.writeValueAsString(patch)));
            } catch (Exception e) {
                // Client went away — the run continues; the outcome still lands
                // in the run history when the caller asked to keep it.
            }
        }
    }

    /**
     * Renders a finished run: the step trace first, the outcome last. The
     * outcome sits at the bottom because that is where the eye lands after
     * reading the trace — and it is the one thing the user came for, so it gets
     * a coloured banner (green / amber / red) rather than a line of text.
     */
    @PostMapping("/{wf}/run")
    public UiPage run(@PathVariable String wf, @RequestBody Map<String, Object> params) {
        WorkflowData data = require(wf);
        boolean persist = Boolean.parseBoolean(String.valueOf(params.remove(PERSIST_FIELD)));
        // Remember the submitted inputs so the next run form starts prefilled.
        Map<String, String> remembered = new LinkedHashMap<>();
        params.forEach((k, v) -> { if (v != null) remembered.put(k, String.valueOf(v)); });
        lastRunInputs.put(wf, remembered);
        // The form submits strings; the params schema says which are really
        // arrays, numbers, booleans — coerce them before the engine sees them.
        Map<String, Object> typed = ParamCoercion.coerce(data.getParams(), params);

        // The service persists the instance itself: always on a halt, and on a
        // finished run when the user ticked "keep" — the instance is the record.
        WorkflowRunService.RunReport report = service.run(data, typed, null, persist);
        return renderRun(wf, report);
    }

    /**
     * Asks for what the suspension is waiting on, then continues.
     *
     * <p>A halt can declare the variables it expects back ({@code resumeParams}) —
     * an approver's verdict, a user's next message. Those become a form; the
     * values are assigned into the workflow's root scope, so the steps after the
     * halt read them like any other variable. A halt that declares nothing needs
     * no form and is continued straight away.
     */
    @PostMapping("/{wf}/instance/{instanceId}/continue")
    public UiPatch continuePicker(@PathVariable String wf, @PathVariable String instanceId) {
        WorkflowData data = require(wf);
        WorkflowInstanceSnapshot snapshot = requireHalted(instanceId);
        HaltData halt = WorkflowInstanceSnapshots.pendingHalt(data, snapshot).orElse(null);

        String url = BASE + "/" + wf + "/instance/" + enc(instanceId) + "/resume";
        UiForm form = resumeForm(halt, data, snapshot, url);
        form.action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                .onClick(UiTrigger.api("POST", BASE + "/" + wf + "/close-dialog")));
        return openDialog("Continue this run", form);
    }

    /**
     * The form that collects the resume input. A {@link FormStepData} carries its
     * own semantic-ui form, so we render that and just wire the submit; any other
     * halt is described by its {@code resumeParams} schema, which we turn into
     * fields. Either way the submit posts to {@code url}, and the fields' ids
     * become the params the run resumes with.
     */
    private UiForm resumeForm(HaltData halt, WorkflowData data,
                              WorkflowInstanceSnapshot snapshot, String url) {
        // Runtime-built form: formFrom names a scope variable (map or JSON
        // string in UiNode shape) — this is how a workflow shows forms whose
        // fields depend on runtime data (one checkbox per section, ...).
        if (halt instanceof FormStepData fs && fs.getFormFrom() != null && !fs.getFormFrom().isBlank()) {
            UiForm dynamic = dynamicForm(fs.getFormFrom(), data, snapshot);
            if (dynamic != null) {
                dynamic.action(UiAction.primary("go", "Continue").icon("forward")
                        .dispatch("POST", url, dynamic.getId()));
                return dynamic;
            }
        }
        if (halt instanceof FormStepData fs && fs.getForm() instanceof UiForm authored) {
            authored.action(UiAction.primary("go", "Continue").icon("forward")
                    .dispatch("POST", url, authored.getId()));
            return authored;
        }
        Schema expected = halt == null ? Schema.object() : halt.getResumeParams();
        UiForm form = UiForm.of("resume-form", ParamFormFields.isEmpty(expected)
                ? "Nothing is expected — continue where it stopped."
                : "This run is waiting for:");
        ParamFormFields.addTo(form, expected);
        form.action(UiAction.primary("go", "Continue").icon("forward").dispatch("POST", url, "resume-form"));
        return form;
    }

    /**
     * Reads {@code variable} from the suspended run and deserialises it as a
     * UiForm. The instance is RESTORED for the lookup — the same path resume
     * takes — so variable resolution uses the engine's own scope semantics
     * (and the definition-fingerprint check) instead of an admin-side
     * reimplementation. The restored instance is discarded afterwards.
     */
    private UiForm dynamicForm(String variable, WorkflowData data, WorkflowInstanceSnapshot snapshot) {
        ai.mindconnect.workflow.execution.WorkflowInstance instance =
                ai.mindconnect.workflow.persist.WorkflowInstanceSnapshots.restore(
                        data, snapshot, ai.mindconnect.workflow.spi.SpiWorkflowContextFactory.create().instantiate(data.getName()));
        Object value = instance.getVariableScope().getVariableValue(variable);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String json) {
                return (UiForm) objectMapper.readValue(json, ai.mindconnect.ui.model.UiNode.class);
            }
            return (UiForm) objectMapper.convertValue(value, ai.mindconnect.ui.model.UiNode.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("formFrom variable '" + variable
                    + "' does not hold a valid form: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming resume, hop 1 (same two-hop pattern as {@code /run/start}):
     * park the coerced resume params with the snapshot, answer with the
     * live-run page; the bus opens the stream, which continues the workflow
     * and pushes per-step progress. A halt renders the Resume/Log tabs again.
     */
    @PostMapping("/{wf}/instance/{instanceId}/resume/start")
    public UiPage startResume(@PathVariable String wf, @PathVariable String instanceId,
                              @RequestBody Map<String, Object> params) {
        WorkflowData data = require(wf);
        WorkflowInstanceSnapshot snapshot = requireHalted(instanceId);
        Schema expected = WorkflowInstanceSnapshots.pendingHalt(data, snapshot)
                .map(HaltData::getResumeParams).orElse(null);
        Map<String, Object> typed = ParamCoercion.coerce(expected, params);

        String runId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        liveRuns.put(runId, new LiveRun(wf, data, typed, true, snapshot, "wf-run-live-" + runId));
        evictFinishedLiveRuns();
        return liveRunPage(wf, runId, liveRuns.get(runId));
    }

    /** Continues a suspended run with the values the halt was waiting for. */
    @PostMapping("/{wf}/instance/{instanceId}/resume")
    public UiPage resumeInstance(@PathVariable String wf, @PathVariable String instanceId,
                                 @RequestBody Map<String, Object> params) {
        WorkflowData data = require(wf);
        WorkflowInstanceSnapshot snapshot = requireHalted(instanceId);
        // Coerce against what the halt declared it waits for, same as a run's params.
        Schema expected = WorkflowInstanceSnapshots.pendingHalt(data, snapshot)
                .map(HaltData::getResumeParams).orElse(null);
        return resumeAndRender(wf, data, snapshot, ParamCoercion.coerce(expected, params));
    }

    private UiPage resumeAndRender(String wf, WorkflowData data,
                                   WorkflowInstanceSnapshot snapshot, Map<String, Object> params) {
        // The service overwrites the instance in place: same id, new status,
        // full trace — the history entry follows the run automatically.
        WorkflowRunService.RunReport report = service.resume(data, snapshot, params);
        return renderRun(wf, report);
    }

    private WorkflowInstanceSnapshot requireInstance(String instanceId) {
        return service.requireInstance(instanceId);
    }

    private WorkflowInstanceSnapshot requireHalted(String instanceId) {
        return service.requireHalted(instanceId);
    }

    private UiPage renderRun(String wf, WorkflowRunService.RunReport report) {
        // Parked so the per-step dialogs have something to fetch after this
        // response has been rendered — the run itself is over by then.
        String runId = runStore.put(report);

        String base = "run:" + wf;
        UiStack page = UiStack.of(base).gap(8);
        page.child(UiStack.of(base + ":bar")
                .direction(UiStack.Direction.HORIZONTAL).gap(12)
                .child(UiText.of(base + ":title", "Run of " + wf).withCssClass("wf-run-title"))
                .child(UiAction.primary(base + ":scope", "Variable scope")
                        .onClick(UiTrigger.api("GET", runUrl(wf, runId, "scope")))));

        // The root card's Input shows only what actually went IN — the
        // declared workflow params — not the scope's end state (every
        // exported variable plus resume form fields); that full state stays
        // one click away behind "Variable scope".
        java.util.Set<String> declaredParams = declaredParams(wf);
        for (WorkflowRunService.RunEntry e : report.roots()) {
            page.child(runStepNode(wf, runId, base, e, declaredParams));
        }

        page.child(outcomeBanner(base, report));

        UiStack actions = UiStack.of(base + ":actions")
                .direction(UiStack.Direction.HORIZONTAL).gap(8);
        // instanceId alone is not "suspended" any more — finished kept runs
        // carry the id of their history instance too.
        if (report.outcome() == WorkflowRunService.Outcome.HALTED && report.instanceId() != null) {
            // Suspended: the page becomes Resume/Log tabs — the resume form
            // right there (Continue re-enters the live stream), the trace one
            // tab away. Falls back to the old Continue button if the snapshot
            // is unreadable.
            try {
                WorkflowData data = require(wf);
                WorkflowInstanceSnapshot snapshot = requireInstance(report.instanceId());
                HaltData halt = WorkflowInstanceSnapshots.pendingHalt(data, snapshot).orElse(null);
                String resumeUrl = BASE + "/" + wf + "/instance/"
                        + enc(report.instanceId()) + "/resume/start";
                UiForm form = resumeForm(halt, data, snapshot, resumeUrl);
                var tabs = ai.mindconnect.ui.model.UiSection.of(base + ":halt-tabs", null)
                        .section("resume", "Resume", form)
                        .section("log", "Log", page);
                UiStack halted = UiStack.of(base + ":halted").gap(8).child(tabs);
                return UiPage.of(BASE + "/" + wf + "/run", halted);
            } catch (RuntimeException e) {
                actions.child(UiAction.primary("continue", "Continue this run").icon("forward")
                        .dispatch("POST", BASE + "/" + wf + "/instance/"
                                + enc(report.instanceId()) + "/continue"));
            }
        }
        actions.child(UiAction.primary("back", "Back to workflow").icon("back")
                        .onClick(UiTrigger.go(BASE + "/" + wf)))
                .child(UiAction.primary("rerun", "Run again").icon("refresh")
                        .onClick(UiTrigger.go(BASE + "/" + wf + "/run")));
        page.child(actions);

        return UiPage.of(BASE + "/" + wf + "/run", page);
    }

    /**
     * One executed step and, indented underneath it, whatever it ran inside
     * itself — a for-each's iterations, an if's chosen branch. Mirrors the
     * nesting of the editor's tree, so a run reads as the workflow it came from
     * rather than as a flat log you have to reconstruct the control flow from.
     */
    private UiNode runStepNode(String wf, String runId, String base,
                               WorkflowRunService.RunEntry e,
                               java.util.Set<String> declaredParams) {
        String rid = base + ":" + e.index();
        UiNode card = runStepCard(wf, runId, rid, e.index(), e, declaredParams);

        UiNode box = card;
        if (!e.children().isEmpty()) {
            UiStack inner = UiStack.of(rid + ":children").gap(6);
            inner.withCssClass("wf-nested");
            for (WorkflowRunService.RunEntry child : e.children()) {
                inner.child(runStepNode(wf, runId, base, child, declaredParams));
            }
            box = UiStack.of(rid + ":group").gap(6).child(card).child(inner);
        }

        // A step that assigns a variable reads as "variable = value", with the
        // step box beneath as where that value came from. The name-and-value line
        // sits in front of the whole box (sub-steps included). Clearer than an
        // arrow tucked inside. A failed step assigned nothing, so no prefix.
        if (e.error() == null && e.assignedVar() != null && !e.assignedVar().isBlank()) {
            String assigned = valueOrDash(e.result());
            if (assigned.length() > 100) {
                assigned = assigned.substring(0, 100) + "…";
            }
            String assignment = e.assignedVar() + " = " + assigned;
            return UiStack.of(rid + ":assign").gap(2)
                    .child(UiText.of(rid + ":assign:var", assignment)
                            .withCssClass("wf-run-assign-var"))
                    .child(box);
        }
        return box;
    }

    /**
     * One executed step, as one line: the name (opens the details dialog) and
     * the duration. State only shouts when it is not plain success — a log
     * where every row says FINISHED says nothing. Everything else (input,
     * output, logs, scope, instance) lives in the dialog.
     */
    private UiNode runStepCard(String wf, String runId, String rid, int index,
                               WorkflowRunService.RunEntry e,
                               java.util.Set<String> declaredParams) {
        UiStack row = UiStack.of(rid)
                .direction(UiStack.Direction.HORIZONTAL).gap(8)
                .child(UiAction.link(rid + ":open", e.name())
                        .onClick(UiTrigger.api("GET", runStepUrl(wf, runId, index, "details"))))
                .child(UiText.of(rid + ":duration", e.durationInMs() + " ms")
                        .withCssClass("wf-run-meta"));
        if (!"FINISHED".equals(e.state())) {
            row.child(UiText.of(rid + ":state", e.state())
                    .withCssClass("wf-run-state wf-run-state--" + e.state().toLowerCase()));
        }
        row.withCssClass("wf-run-step");
        return row;
    }

    // -----------------------------------------------------------------------
    // Run inspection — log / instance per step, variable scope for the whole run
    // -----------------------------------------------------------------------

    /**
     * Everything about one executed step, as one dialog: what it was and what
     * it produced up front, its log, scope and raw instance one tab away. The
     * run view's step rows link here — the row itself stays name + duration.
     */
    @GetMapping("/{wf}/run/{runId}/step/{index}/details")
    public UiPatch runStepDetails(@PathVariable String wf, @PathVariable String runId,
                                  @PathVariable int index) {
        WorkflowRunService.RunEntry e = runEntry(runId, index);
        String id = "run-details";

        UiStack general = UiStack.of(id + ":general").gap(6);
        general.child(UiStack.of(id + ":head")
                .direction(UiStack.Direction.HORIZONTAL).gap(8)
                .child(UiText.of(id + ":type", e.type()).withCssClass("wf-step-type"))
                .child(UiText.of(id + ":state", e.state())
                        .withCssClass("wf-run-state wf-run-state--" + e.state().toLowerCase()))
                .child(UiText.of(id + ":timing", timing(e)).withCssClass("wf-run-meta")));

        // Input: the variables the step brought in on its own scope — a loop's
        // item/index. The workflow ROOT is special: its scope is the run's
        // whole end state, so only the declared params render as Input there.
        String input = "workflow".equals(e.type())
                ? ownVarsFiltered(e.scope(), declaredParams(wf))
                : ownVarsFull(e.scope());
        if (input != null) {
            general.child(labelledCollapsible(id + ":input", "Input", input, "wf-run-result"));
        }
        if (e.error() != null) {
            general.child(labelledCollapsible(id + ":output", "Error", e.error(), "wf-run-error-text"));
        } else {
            general.child(labelledCollapsible(id + ":output", "Output", valueOrDash(e.result()), "wf-run-result"));
        }
        if (e.assignedVar() != null && !e.assignedVar().isBlank()) {
            general.child(labelled(id + ":assigned", "Assigned to", e.assignedVar()));
        }
        if (e.ref() != null) {
            // Swaps this dialog for the editor's own step-detail dialog, so the
            // definition looks identical whether you reach it while editing or
            // while reading a run.
            general.child(UiAction.secondary(id + ":definition", "Step Definition")
                    .onClick(UiTrigger.api("GET", stepUrl(wf, e.ref(), ""))));
        }

        var tabs = ai.mindconnect.ui.model.UiSection.of(id + ":tabs", null)
                .section("details", "Details", general)
                .section("log", "Log", logBody(e))
                .section("scope", "Variable scope",
                        scopeTree("step-scope", e.name() + "  (" + e.type() + ")", e.scope()))
                .section("instance", "StepInstance",
                        UiText.of(id + ":instance", e.instanceJson()).withCssClass("wf-run-json"));
        return openDialog(e.type() + " — " + e.name(), tabs);
    }

    private java.util.Set<String> declaredParams(String wf) {
        try {
            Schema params = require(wf).getParams();
            return params != null && params.getProperties() != null
                    ? params.getProperties().keySet() : java.util.Set.of();
        } catch (RuntimeException ignored) {
            return java.util.Set.of();
        }
    }

    private UiNode logBody(WorkflowRunService.RunEntry entry) {
        UiStack body = UiStack.of("run-log").gap(2);
        if (entry.logs().isEmpty()) {
            body.child(UiText.of("run-log:empty", "This step logged nothing.")
                    .withCssClass("wf-run-result"));
        } else {
            int i = 0;
            for (WorkflowRunService.LogLine line : entry.logs()) {
                body.child(UiText.of("run-log:" + (i++), line.level() + "  " + line.message())
                        .withCssClass("wf-run-log-line"));
            }
        }
        return body;
    }

    /** The step's log lines, in the order the engine emitted them. */
    @GetMapping("/{wf}/run/{runId}/step/{index}/log")
    public UiPatch runStepLog(@PathVariable String wf, @PathVariable String runId,
                              @PathVariable int index) {
        WorkflowRunService.RunEntry entry = runEntry(runId, index);
        return openDialog("Log — " + entry.name(), logBody(entry));
    }

    /** The step instance's own state as JSON (definition, timings, result). */
    @GetMapping("/{wf}/run/{runId}/step/{index}/instance")
    public UiPatch runStepInstance(@PathVariable String wf, @PathVariable String runId,
                                   @PathVariable int index) {
        WorkflowRunService.RunEntry entry = runEntry(runId, index);
        UiNode body = UiText.of("run-instance", entry.instanceJson())
                .withCssClass("wf-run-json");
        return openDialog("StepInstance — " + entry.name(), body);
    }

    /**
     * The {@code WorkflowInstance}'s own variable scope — the workflow-level
     * variables as the run left them. This is the run's scope, not any step's.
     */
    @GetMapping("/{wf}/run/{runId}/scope")
    public UiPatch runScope(@PathVariable String wf, @PathVariable String runId) {
        WorkflowRunService.RunReport report = runStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("This run is no longer available."));
        return openDialog("Variable scope — " + wf,
                scopeTree("run-scope", "Workflow instance", report.scope()));
    }

    /**
     * The scope of one step, as it stood when that step finished — shaped like
     * the {@code VariableScope} itself: this scope's own variables, then its
     * {@code parentScope} as a node you can open.
     */
    @GetMapping("/{wf}/run/{runId}/step/{index}/scope")
    public UiPatch runStepScope(@PathVariable String wf, @PathVariable String runId,
                                @PathVariable int index) {
        WorkflowRunService.RunEntry entry = runEntry(runId, index);
        return openDialog("Variable scope — " + entry.name(),
                scopeTree("step-scope", entry.name() + "  (" + entry.type() + ")", entry.scope()));
    }

    /**
     * A scope as a tree, mirroring {@code VariableScope}: the variables declared
     * directly in it, then a {@code parentScope} branch holding the scope it
     * inherits from — which is exactly how a lookup walks it. The inner scope is
     * open, the parents collapsed: what the step itself declared is the answer
     * you usually want, the inherited levels are there when you go looking.
     */
    private UiTree scopeTree(String id, String title, WorkflowRunService.ScopeSnapshot scope) {
        UiTree tree = UiTree.of(id, title);
        if (scope == null) {
            tree.node(UiTreeNode.of(id + ":empty", "(no scope recorded)"));
            return tree;
        }
        tree.node(scopeNode(id, scope, true));
        return tree;
    }

    private UiTreeNode scopeNode(String id, WorkflowRunService.ScopeSnapshot scope, boolean open) {
        UiTreeNode node = UiTreeNode.of(id, scope.name()).icon("◻").open(open);

        if (scope.variables().isEmpty()) {
            node.child(UiTreeNode.of(id + ":empty", "(no variables)"));
        } else {
            int v = 0;
            for (WorkflowRunService.VarSnapshot var : scope.variables()) {
                node.child(variableNode(id + ":" + (v++), var));
            }
        }

        if (scope.parentScope() != null) {
            UiTreeNode parent = UiTreeNode.of(id + ":parent", "parentScope").open(false);
            parent.child(scopeNode(id + ":parent:scope", scope.parentScope(), false));
            node.child(parent);
        }
        return node;
    }

    /**
     * One variable in the scope tree. Maps and collections become collapsed
     * branches rather than one long line — {@code env} alone is the whole
     * process environment, and printed inline it drowns out every variable the
     * workflow actually set.
     */
    private UiTreeNode variableNode(String id, WorkflowRunService.VarSnapshot var) {
        if (!var.hasEntries()) {
            return UiTreeNode.of(id, var.name() + " = " + var.value() + "   [" + var.type() + "]");
        }
        UiTreeNode node = UiTreeNode.of(id, var.name() + "   [" + var.type()
                + ", " + var.value() + "]").open(false);
        int i = 0;
        for (WorkflowRunService.VarSnapshot entry : var.entries()) {
            node.child(variableNode(id + ":" + (i++), entry));
        }
        return node;
    }

    private WorkflowRunService.RunEntry runEntry(String runId, int index) {
        WorkflowRunService.RunReport report = runStore.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("This run is no longer available."));
        List<WorkflowRunService.RunEntry> steps = report.steps();
        if (index < 0 || index >= steps.size()) {
            throw new IllegalArgumentException("No step " + index + " in this run.");
        }
        return steps.get(index);
    }

    private static String runUrl(String wf, String runId, String op) {
        return BASE + "/" + wf + "/run/" + runId + "/" + op;
    }

    private static String runStepUrl(String wf, String runId, int index, String op) {
        return BASE + "/" + wf + "/run/" + runId + "/step/" + index + "/" + op;
    }

    /** A "Label: value" line — label muted, value in the given (monospace) class. */
    private UiNode labelled(String id, String label, String value) {
        return labelled(id, label, value, "wf-run-result");
    }

    /** Values above this length collapse behind a summary line in the run view. */
    private static final int INLINE_VALUE_LIMIT = 160;

    /**
     * Short values render inline; long ones (a resume's form payload, a big
     * JSON variable) collapse behind "Label (size) — first line…" so step
     * cards stay scannable. Client-side collapse: the user's toggle survives
     * live-stream re-renders.
     */
    private UiNode labelledCollapsible(String id, String label, String value, String valueClass) {
        if (value == null || (value.length() <= INLINE_VALUE_LIMIT && !value.contains("\n"))) {
            return labelled(id, label, value, valueClass);
        }
        String firstLine = value.strip();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) firstLine = firstLine.substring(0, newline);
        if (firstLine.length() > 90) firstLine = firstLine.substring(0, 90);
        String summary = label + " (" + readableSize(value.length()) + ")  " + firstLine + "…";
        UiList list = UiList.of(id, null).withCssClass("wf-run-collapsible");
        list.item(UiList.Item.of(id + ":item", label)
                .content(UiText.of(id + ":v", value).withCssClass(valueClass))
                .collapsibleClient(summary, id + ":sum"));
        return list;
    }

    private static String readableSize(int chars) {
        return chars < 1024 ? chars + " chars" : String.format("%.1f KB", chars / 1024.0);
    }

    private UiNode labelled(String id, String label, String value, String valueClass) {
        return UiStack.of(id).direction(UiStack.Direction.HORIZONTAL).gap(6)
                .child(UiText.of(id + ":k", label).withCssClass("wf-run-label"))
                .child(UiText.of(id + ":v", value).withCssClass(valueClass));
    }

    /**
     * The variables a step brought in on its own scope, as a compact
     * {@code name=value} line — {@code item=6, i=1} for a loop iteration, the
     * params for the workflow root. {@code env} is skipped (it is the whole
     * process environment) and long values are clipped. Null when there is
     * nothing worth a line.
     */
    /** Only the given variable names (the declared params), one per line; null when none present. */
    private static String ownVarsFiltered(WorkflowRunService.ScopeSnapshot scope,
                                          java.util.Set<String> names) {
        if (scope == null || scope.variables().isEmpty() || names.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WorkflowRunService.VarSnapshot var : scope.variables()) {
            if (var.name() == null || !names.contains(var.name())) continue;
            String value = var.hasEntries() ? "[" + var.value() + "]" : var.value();
            if (sb.length() > 0) sb.append('\n');
            sb.append(var.name()).append(" = ").append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Like {@link #ownVars} but uncut, one variable per line — for collapsibles. */
    private static String ownVarsFull(WorkflowRunService.ScopeSnapshot scope) {
        if (scope == null || scope.variables().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WorkflowRunService.VarSnapshot var : scope.variables()) {
            if (var.name() == null || var.name().isBlank() || "env".equals(var.name())) continue;
            String value = var.hasEntries() ? "[" + var.value() + "]" : var.value();
            if (sb.length() > 0) sb.append('\n');
            sb.append(var.name()).append(" = ").append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String ownVars(WorkflowRunService.ScopeSnapshot scope) {
        if (scope == null || scope.variables().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (WorkflowRunService.VarSnapshot var : scope.variables()) {
            if (var.name() == null || var.name().isBlank() || "env".equals(var.name())) continue;
            String value = var.hasEntries() ? "[" + var.value() + "]" : var.value();
            if (value != null && value.length() > 80) {
                value = value.substring(0, 80) + "…";
            }
            if (sb.length() > 0) sb.append(",  ");
            sb.append(var.name()).append('=').append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The closing banner: what the run produced, colour-coded by outcome. */
    private UiNode outcomeBanner(String base, WorkflowRunService.RunReport report) {
        String headline;
        String detail;
        String modifier;
        switch (report.outcome()) {
            case SUCCESS -> {
                headline = "✓ Success";
                detail = "Result: " + valueOrDash(report.result());
                modifier = "success";
            }
            case HALTED -> {
                headline = "⏸ Halted";
                detail = "The workflow suspended at a halt step and can be continued."
                        + (report.result() == null ? "" : " Result so far: " + report.result());
                modifier = "halted";
            }
            default -> {
                headline = "✗ Failed";
                detail = report.error();
                modifier = "error";
            }
        }
        UiStack banner = UiStack.of(base + ":outcome").gap(4)
                .child(UiText.of(base + ":outcome:headline", headline)
                        .withCssClass("wf-run-outcome-headline"))
                .child(UiText.of(base + ":outcome:detail", detail)
                        .withCssClass("wf-run-outcome-detail"));
        banner.withCssClass("wf-run-outcome wf-run-outcome--" + modifier);
        return banner;
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? "—" : value;
    }

    /** Millisecond precision: most steps finish inside one millisecond, and a
     *  second-resolution stamp would render every one of them as "0s". */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** "started → ended · duration" for one step. */
    private static String timing(WorkflowRunService.RunEntry e) {
        return stamp(e.startTime()) + "  →  " + stamp(e.endTime())
                + "   ·   " + e.durationInMs() + " ms";
    }

    private static String stamp(long epochMillis) {
        return epochMillis <= 0 ? "—" : STAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** A step's name doubles as a URL segment; a slash would split the path. */
    private static final Pattern ILLEGAL_IN_NAME = Pattern.compile("[/\\\\]");

    /**
     * Loads a workflow and makes it addressable: every step gets a unique,
     * readable name. A hand-written file may have unnamed or duplicate steps,
     * and neither can serve as an address — nor, for that matter, as a
     * {@code jumpTo} target. Normalising once on load (and persisting it) is
     * what lets every URL below be a plain name.
     */
    private WorkflowData require(String wf) {
        return service.require(wf);
    }

    private WorkflowMutator.StepLocation requireLocation(WorkflowData data, String ref) {
        WorkflowMutator.StepLocation loc = mutator.find(data, ref);
        if (loc == null) {
            throw new NoSuchStepException(ref);
        }
        return loc;
    }

    private StepData requireStep(WorkflowData data, String ref) {
        return requireLocation(data, ref).step();
    }

    /**
     * Why {@code name} cannot be used, or null if it can. {@code currentRef} is
     * the step being renamed (which may of course keep its own name), or null
     * when naming a brand-new step.
     */
    private String rejectName(WorkflowData data, String currentRef, String name) {
        if (name.isBlank()) {
            return "A step needs a name — it is how the editor and jumpTo address it.";
        }
        if (ILLEGAL_IN_NAME.matcher(name).find()) {
            return "A step name cannot contain '/' or '\\'.";
        }
        if (!name.equals(currentRef) && StepNames.isTaken(data, name)) {
            return "Another step is already called '" + name + "'.";
        }
        return null;
    }

    /** Persist, then repaint the step list in place and close any open dialog. */
    // -----------------------------------------------------------------------
    // Parameters — the workflow's typed input schema (WorkflowData.params).
    // This is also the tool signature an agent's LLM sees when the workflow
    // is exposed as a tool, so add/edit/remove and the type live here.
    // -----------------------------------------------------------------------

    /** The Parameters tab: one card per declared input, plus an add control. */
    private UiNode paramsTab(String wf, WorkflowData data) {
        UiStack box = UiStack.of("wf-params:" + wf).gap(6);
        Schema params = data.getParams();
        Map<String, Schema> props = params == null ? Map.of() : params.getProperties();
        if (props.isEmpty()) {
            box.child(UiText.of("wf-params:" + wf + ":empty",
                    "No parameters declared — this workflow runs without inputs."));
        } else {
            props.forEach((name, prop) -> {
                String meta = paramTypeLabel(prop)
                        + (params.isRequired(name) ? ", required" : "")
                        + (prop.getDefaultValue() != null ? ", default: " + prop.getDefaultValue() : "");
                String desc = prop.getDescription() == null || prop.getDescription().isBlank()
                        ? "" : " — " + prop.getDescription();
                UiStack row = UiStack.of("wf-param:" + wf + ":" + name)
                        .direction(UiStack.Direction.HORIZONTAL).gap(12)
                        .child(UiText.of("wf-param:" + wf + ":" + name + ":text",
                                name + "  (" + meta + ")" + desc))
                        .child(UiAction.secondary("edit-param-" + name, "Edit")
                                .dispatch("GET", BASE + "/" + wf + "/params/" + enc(name) + "/edit"))
                        .child(UiAction.danger("delete-param-" + name, "Delete")
                                .confirm("Delete parameter '" + name + "'?")
                                .dispatch("POST", BASE + "/" + wf + "/params/" + enc(name) + "/delete"));
                box.child(row);
            });
        }
        box.child(UiAction.primary("add-param", "Add parameter").icon("add")
                .dispatch("GET", BASE + "/" + wf + "/params/new"));
        return box;
    }

    @GetMapping("/{wf}/params/new")
    public UiPatch paramNew(@PathVariable String wf) {
        require(wf);
        return openDialog("Add parameter", paramForm(wf, null, null, false));
    }

    @GetMapping("/{wf}/params/{name}/edit")
    public UiPatch paramEdit(@PathVariable String wf, @PathVariable String name) {
        WorkflowData data = require(wf);
        Schema prop = requireParam(data, name);
        return openDialog("Edit parameter '" + name + "'",
                paramForm(wf, name, prop, data.getParams().isRequired(name)));
    }

    @PostMapping("/{wf}/params/create")
    public UiPatch paramCreate(@PathVariable String wf, @RequestBody Map<String, Object> form) {
        return paramUpsert(wf, null, form);
    }

    @PostMapping("/{wf}/params/{name}/save")
    public UiPatch paramSave(@PathVariable String wf, @PathVariable String name,
                             @RequestBody Map<String, Object> form) {
        return paramUpsert(wf, name, form);
    }

    @PostMapping("/{wf}/params/{name}/delete")
    public UiPatch paramDelete(@PathVariable String wf, @PathVariable String name) {
        WorkflowData data = require(wf);
        requireParam(data, name);
        data.getParams().getProperties().remove(name);
        data.getParams().getRequired().remove(name);
        return paramsSaveAndRefresh(wf, data, UiToast.success("Parameter '" + name + "' deleted."));
    }

    /** Shared create/edit path: validate the name, build the property schema, replace or append. */
    private UiPatch paramUpsert(String wf, String originalName, Map<String, Object> form) {
        WorkflowData data = require(wf);
        Schema params = data.getParams();

        String name = String.valueOf(form.getOrDefault("name", "")).trim();
        String dialogTitle = originalName == null ? "Add parameter" : "Edit parameter '" + originalName + "'";
        if (name.isBlank()) {
            return openDialog(dialogTitle, paramFormFrom(wf, originalName, form))
                    .toast(UiToast.error("The parameter needs a name.").title("Cannot save parameter"));
        }
        boolean rename = originalName == null || !originalName.equals(name);
        if (rename && params.getProperties().containsKey(name)) {
            return openDialog(dialogTitle, paramFormFrom(wf, originalName, form))
                    .toast(UiToast.error("A parameter named '" + name + "' already exists.")
                            .title("Cannot save parameter"));
        }
        if (originalName != null) {
            requireParam(data, originalName);
        }

        Schema prop = paramSchemaFrom(form);
        boolean required = Boolean.parseBoolean(String.valueOf(form.get("required")));

        if (originalName == null || originalName.equals(name)) {
            params.getProperties().put(name, prop);
        } else {
            // Rename in place, preserving declaration order (it is also the form order).
            Map<String, Schema> rebuilt = new LinkedHashMap<>();
            params.getProperties().forEach((key, value) ->
                    rebuilt.put(key.equals(originalName) ? name : key, key.equals(originalName) ? prop : value));
            params.getProperties().clear();
            params.getProperties().putAll(rebuilt);
            params.getRequired().remove(originalName);
        }
        params.getRequired().remove(name);
        if (required) {
            params.getRequired().add(name);
        }
        return paramsSaveAndRefresh(wf, data, UiToast.success("Parameter '" + name + "' saved."));
    }

    /**
     * The add/edit dialog form. {@code originalName == null} means "add". Fields
     * irrelevant to the selected type are omitted; the type select re-submits
     * the form to the {@code /form} endpoint on change, which re-renders this
     * dialog with the fields the new type needs (the user's values survive the
     * round-trip because the whole form is the payload).
     */
    private UiForm paramForm(String wf, String originalName, Schema prop, boolean required) {
        return paramForm(wf, originalName, originalName, prop, required);
    }

    private UiForm paramForm(String wf, String originalName, String nameValue, Schema prop, boolean required) {
        boolean isNew = originalName == null;
        String formId = "param-form:" + wf;
        String type = prop == null ? "string" : paramTypeValue(prop);
        String refreshUrl = isNew ? BASE + "/" + wf + "/params/form"
                                  : BASE + "/" + wf + "/params/" + enc(originalName) + "/form";

        UiForm form = UiForm.of(formId, isNew ? "Add parameter" : "Edit parameter");
        form.field(UiField.text("name", "Name", nameValue)
                .asEditable().asRequired()
                .hint("The input's key — also the argument name an agent's LLM uses"));
        form.field(UiField.select("type", "Type", type, paramTypeOptions())
                .asEditable().asRequired()
                .onChange(UiTrigger.api("POST", refreshUrl, formId)));
        if ("enum".equals(type)) {
            form.field(UiField.text("enumValues", "Enum values",
                            prop != null && prop.getType() == Schema.Type.ENUM
                                    ? String.join(", ", prop.getEnumValues()) : null)
                    .asEditable().asRequired()
                    .hint("Comma-separated list of the allowed values"));
        }
        if ("array".equals(type)) {
            String itemType = prop != null && prop.getItems() != null
                    ? prop.getItems().getType().name().toLowerCase() : "string";
            form.field(UiField.select("itemType", "Item type", itemType, itemTypeOptions())
                    .asEditable()
                    .hint("The type of each array element"));
        }
        form.field(UiField.text("description", "Description",
                        prop == null ? null : prop.getDescription())
                .asEditable()
                .hint("Shown in run forms and in the LLM tool schema"));
        if (!"array".equals(type) && !"object".equals(type)) {
            form.field(UiField.text("defaultValue", "Default value",
                            prop == null || prop.getDefaultValue() == null
                                    ? null : String.valueOf(prop.getDefaultValue()))
                    .asEditable());
        }
        form.field(UiField.bool("required", "Required", required).asEditable());
        String saveUrl = isNew ? BASE + "/" + wf + "/params/create"
                               : BASE + "/" + wf + "/params/" + enc(originalName) + "/save";
        form.action(UiAction.primary("save", "Save").icon("save").dispatch("POST", saveUrl, formId));
        form.action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                .dispatch("POST", BASE + "/" + wf + "/close-dialog"));
        return form;
    }

    /** Re-renders the add dialog for the submitted values (type change swaps the visible fields). */
    @PostMapping("/{wf}/params/form")
    public UiPatch paramFormRefresh(@PathVariable String wf, @RequestBody Map<String, Object> form) {
        require(wf);
        return openDialog("Add parameter", paramFormFrom(wf, null, form));
    }

    /** Same as {@link #paramFormRefresh}, for the edit dialog of {@code name}. */
    @PostMapping("/{wf}/params/{name}/form")
    public UiPatch paramFormRefreshEdit(@PathVariable String wf, @PathVariable String name,
                                        @RequestBody Map<String, Object> form) {
        WorkflowData data = require(wf);
        requireParam(data, name);
        return openDialog("Edit parameter '" + name + "'", paramFormFrom(wf, name, form));
    }

    /** Rebuilds the dialog from a submission (type change, rejection) so the user's input survives. */
    private UiForm paramFormFrom(String wf, String originalName, Map<String, Object> form) {
        Schema prop = paramSchemaFrom(form);
        boolean required = Boolean.parseBoolean(String.valueOf(form.get("required")));
        Object typedName = form.get("name");
        String nameValue = typedName == null || String.valueOf(typedName).isBlank()
                ? originalName : String.valueOf(typedName).trim();
        return paramForm(wf, originalName, nameValue, prop, required);
    }

    /** Builds the property {@link Schema} from the submitted form fields. */
    private static Schema paramSchemaFrom(Map<String, Object> form) {
        String type = String.valueOf(form.getOrDefault("type", "string"));
        String enumValues = String.valueOf(form.getOrDefault("enumValues", ""));
        Schema prop = switch (type) {
            case "multiline" -> Schema.string().multiline();
            case "path" -> Schema.string().path();
            case "integer" -> Schema.integer();
            case "number" -> Schema.number();
            case "boolean" -> Schema.bool();
            case "enum" -> Schema.enumOf(Arrays.stream(enumValues.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new));
            case "array" -> Schema.array(itemSchemaFor(
                    String.valueOf(form.getOrDefault("itemType", "string"))));
            case "object" -> Schema.object();
            default -> Schema.string();
        };
        String description = String.valueOf(form.getOrDefault("description", "")).trim();
        if (!description.isEmpty()) {
            prop.description(description);
        }
        String defaultValue = String.valueOf(form.getOrDefault("defaultValue", "")).trim();
        if (!defaultValue.isEmpty()) {
            prop.defaultValue(switch (prop.getType()) {
                case INTEGER -> parseOr(defaultValue, () -> Long.parseLong(defaultValue));
                case NUMBER -> parseOr(defaultValue, () -> Double.parseDouble(defaultValue));
                case BOOLEAN -> Boolean.parseBoolean(defaultValue);
                default -> defaultValue;
            });
        }
        return prop;
    }

    /** Parses via {@code parser}, falling back to the raw string so the engine reports the real error. */
    private static Object parseOr(String raw, java.util.function.Supplier<Object> parser) {
        try {
            return parser.get();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** The form's type value for an existing property (inverse of {@link #paramSchemaFrom}). */
    private static String paramTypeValue(Schema prop) {
        if (prop.getType() == Schema.Type.STRING && prop.getFormat() == Schema.Format.MULTILINE) {
            return "multiline";
        }
        if (prop.getType() == Schema.Type.STRING && prop.getFormat() == Schema.Format.PATH) {
            return "path";
        }
        return prop.getType().name().toLowerCase();
    }

    private static String paramTypeLabel(Schema prop) {
        if (prop.getType() == Schema.Type.ENUM) {
            return "enum: " + String.join(" | ", prop.getEnumValues());
        }
        return paramTypeValue(prop);
    }

    private static List<UiField.Option> paramTypeOptions() {
        return List.of(
                UiField.Option.of("string", "string"),
                UiField.Option.of("multiline", "string (multiline)"),
                UiField.Option.of("path", "file path (with chooser)"),
                UiField.Option.of("integer", "integer"),
                UiField.Option.of("number", "number"),
                UiField.Option.of("boolean", "boolean"),
                UiField.Option.of("enum", "enum"),
                UiField.Option.of("array", "array (JSON)"),
                UiField.Option.of("object", "object (JSON)"));
    }

    /** The element schema for an array parameter's chosen item type. */
    private static Schema itemSchemaFor(String itemType) {
        return switch (itemType) {
            case "integer" -> Schema.integer();
            case "number" -> Schema.number();
            case "boolean" -> Schema.bool();
            case "object" -> Schema.object();
            default -> Schema.string();
        };
    }

    private static List<UiField.Option> itemTypeOptions() {
        return List.of(
                UiField.Option.of("string", "string"),
                UiField.Option.of("integer", "integer"),
                UiField.Option.of("number", "number"),
                UiField.Option.of("boolean", "boolean"),
                UiField.Option.of("object", "object (JSON)"));
    }

    private static Schema requireParam(WorkflowData data, String name) {
        Schema prop = data.getParams() == null ? null : data.getParams().getProperties().get(name);
        if (prop == null) {
            throw new NoSuchStepException("parameter '" + name + "'");
        }
        return prop;
    }

    /**
     * Persists and re-renders the whole tab section (so the "Parameters (n)"
     * label stays correct), landing the user back on the Parameters tab.
     */
    private UiPatch paramsSaveAndRefresh(String wf, WorkflowData data, UiToast toast) {
        service.save(wf, data);
        UiPatch patch = closeDialog()
                .patch(UiPatch.Operation.replace("wf-tabs:" + wf, tabsFor(wf, data, "params")));
        return toast == null ? patch : patch.toast(toast);
    }

    private UiPatch saveAndRefresh(String wf, WorkflowData data, UiToast toast) {
        service.save(wf, data);
        UiPatch patch = closeDialogAndRefresh(wf, data);
        return toast == null ? patch : patch.toast(toast);
    }

    private static String stepUrl(String wf, String ref, String op) {
        String base = BASE + "/" + wf + "/step/" + enc(ref);
        return op.isEmpty() ? base : base + "/" + op;
    }

    private static String addUrl(String wf, String container) {
        return BASE + "/" + wf + "/add/" + enc(container);
    }

    private static String enc(String segment) {
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Errors — a stale button is a 404, not a stack trace
    // -----------------------------------------------------------------------

    /**
     * The step a button pointed at is gone. With positional addressing this
     * case could not even be detected — the path simply resolved to whatever
     * step had shifted into that slot. By name it is a clean miss, so say so.
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class NoSuchStepException extends RuntimeException {
        NoSuchStepException(String ref) {
            super("No step named '" + ref + "' — it may have been deleted or renamed.");
        }
    }

}
