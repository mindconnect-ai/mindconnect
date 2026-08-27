package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.clusterdemo.TaskType;
import ai.mindconnect.taskqueue.clusterdemo.TaskTypes;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiFieldGroup;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiAction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The "New Task" form: a type select whose change swaps the parameter field
 * group ({@code task-params}) via patch, and a submit that turns the flat form
 * values into a {@link ai.mindconnect.taskqueue.TaskSubmission} and lands on
 * the cluster dashboard, where the work is watched.
 */
@RestController
@RequestMapping("/tasks")
public class NewTaskController {

    private static final String FORM_ID = "new-task-form";

    private final TaskTypes taskTypes;
    private final TaskQueue queue;
    private final ClusterDashboardRenderer dashboard;

    public NewTaskController(TaskTypes taskTypes, TaskQueue queue, ClusterDashboardRenderer dashboard) {
        this.taskTypes = taskTypes;
        this.queue = queue;
        this.dashboard = dashboard;
    }

    @GetMapping("/new")
    public UiPage newTask() {
        return UiPage.of("/tasks/new", form(taskTypes.all().get(0), null));
    }

    @PostMapping("/field-groups")
    public UiPatch fieldGroups(@RequestBody Map<String, Object> values) {
        TaskType type = selectedType(values);
        return UiPatch.of().patch(UiPatch.Operation.replace("task-params", paramsGroup(type)));
    }

    @PostMapping
    public UiPage submit(@RequestBody Map<String, Object> values) {
        TaskType type = selectedType(values);
        try {
            String id = queue.submit(type.toSubmission().apply(values));
            // Land where the work is watched: the cluster dashboard.
            UiPage page = ClusterUiController.withStream(dashboard.page());
            page.toast(UiToast.success("Submitted " + type.label() + " (" + id + ")"));
            return page;
        } catch (IllegalArgumentException e) {
            UiForm form = form(type, e.getMessage());
            return UiPage.of("/tasks/new", form);
        }
    }

    private TaskType selectedType(Map<String, Object> values) {
        String id = String.valueOf(values.get("taskType"));
        return taskTypes.byId(id).orElse(taskTypes.all().get(0));
    }

    private UiForm form(TaskType selected, String error) {
        var options = taskTypes.all().stream()
                .map(t -> UiField.Option.of(t.id(), t.label()))
                .toList();
        var form = UiForm.of(FORM_ID, "Submit a task")
                .field(UiField.select("taskType", "Task type", selected.id(), options)
                        .asEditable().asRequired()
                        .onChange(UiTrigger.api("POST", "/tasks/field-groups", FORM_ID)))
                .content(paramsGroup(selected))
                .action(UiAction.primary("submit", "Submit").icon("add")
                        .dispatch("POST", "/tasks", FORM_ID));
        if (error != null) {
            form.error(error);
        }
        return form;
    }

    private UiFieldGroup paramsGroup(TaskType type) {
        var group = UiFieldGroup.of("task-params", type.label()).hint(type.description());
        type.fields().get().forEach(group::field);
        return group;
    }
}
