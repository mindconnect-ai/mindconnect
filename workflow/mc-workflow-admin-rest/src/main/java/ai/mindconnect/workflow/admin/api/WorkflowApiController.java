package ai.mindconnect.workflow.admin.api;

import ai.mindconnect.workflow.admin.run.WorkflowRunService;
import ai.mindconnect.workflow.admin.service.WorkflowAdminService;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * External REST API for workflows — a thin shell over
 * {@link WorkflowAdminService}, which the admin UI uses too: definition CRUD,
 * synchronous and streaming runs, and resuming halted instances.
 *
 * <p>Definitions travel as <em>opaque JSON</em> ({@link JsonNode}): the
 * payload is exactly the persisted workflow document (with its {@code @class}
 * polymorphism), parsed and written with the workflow object mapper. That
 * keeps this API stable against step-type evolution — and keeps the OpenAPI
 * schema a plain object instead of the recursive step-container tree.
 */
@Tag(name = "Workflows", description = "Workflow definitions (opaque JSON documents), "
        + "synchronous and streaming runs, and halted instances that can be resumed.")
@RestController
public class WorkflowApiController {

    /** The outcome of a run or resume call. */
    public record RunResult(String outcome, String instanceId, String result, String error) {}

    /** One stored run (the instance IS the run record). */
    public record InstanceInfo(String instanceId, String workflowName, String status,
                               long startedAt, long suspendedAt) {}

    private static final ObjectMapper WORKFLOW_MAPPER = WorkflowObjectMapperFactory.create();

    private final WorkflowAdminService service;

    public WorkflowApiController(WorkflowAdminService service) {
        this.service = service;
    }

    // ── Definitions ────────────────────────────────────────────────────────

    @Operation(summary = "List workflows")
    @GetMapping("/api/workflows")
    public List<WorkflowAdminService.WorkflowInfo> list() {
        return service.list(null);
    }

    @Operation(summary = "Get a workflow definition",
            description = "Returns the persisted workflow document verbatim (with its "
                    + "@class step polymorphism) — the same JSON a PUT accepts.")
    @GetMapping("/api/workflows/{id}")
    public ResponseEntity<JsonNode> get(@PathVariable String id) {
        return service.find(id)
                .map(wf -> ResponseEntity.ok((JsonNode) WORKFLOW_MAPPER.valueToTree(wf)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Creates or replaces the definition stored under {@code id}. */
    @Operation(summary = "Create or replace a workflow definition",
            description = "The body is the full workflow document as returned by GET; "
                    + "400 when it does not parse as a workflow.")
    @PutMapping("/api/workflows/{id}")
    public ResponseEntity<WorkflowAdminService.WorkflowInfo> save(@PathVariable String id,
                                                                  @RequestBody JsonNode definition) {
        WorkflowData wf;
        try {
            wf = WORKFLOW_MAPPER.treeToValue(definition, WorkflowData.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        service.save(id, wf);
        return ResponseEntity.ok(new WorkflowAdminService.WorkflowInfo(id,
                wf.getName() != null && !wf.getName().isBlank() ? wf.getName() : id,
                wf.getSteps() != null ? wf.getSteps().size() : 0));
    }

    @Operation(summary = "Delete a workflow definition")
    @DeleteMapping("/api/workflows/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id) ? ResponseEntity.noContent().build()
                                  : ResponseEntity.notFound().build();
    }

    // ── Runs ───────────────────────────────────────────────────────────────

    /**
     * Runs the workflow synchronously with the given parameters. A halted run
     * (form / halt step) persists its instance and reports {@code HALTED} with
     * the {@code instanceId} to resume; {@code persist=true} also keeps
     * finished runs as inspectable history.
     */
    @Operation(summary = "Run a workflow",
            description = "Synchronous run with the given parameters. A halted run (form/"
                    + "halt step) persists its instance and reports HALTED with the "
                    + "instanceId to resume; persist=true also keeps finished runs as "
                    + "inspectable history.")
    @PostMapping("/api/workflows/{id}/run")
    public ResponseEntity<RunResult> run(@PathVariable String id,
                                         @RequestBody(required = false) Map<String, Object> params,
                                         @RequestParam(defaultValue = "false") boolean persist) {
        WorkflowData wf = service.find(id).orElse(null);
        if (wf == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toResult(service.run(wf, params, null, persist)));
    }

    /**
     * Like {@link #run}, but streams the run's progress as Server-Sent
     * Events: one {@code step} event per executed step (name, type, state),
     * a final {@code result} event carrying the {@link RunResult}, then the
     * stream closes. Errors surface both as a step event with state
     * {@code ERROR} and in the result.
     */
    @Operation(summary = "Run a workflow (SSE progress stream)",
            description = "Like run, but streams progress as Server-Sent Events: one "
                    + "'step' event per executed step (name, type, state), then a final "
                    + "'result' event with the RunResult.")
    @PostMapping(value = "/api/workflows/{id}/run/stream",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streaming(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> params,
            @RequestParam(defaultValue = "false") boolean persist) {
        WorkflowData wf = service.find(id).orElse(null);
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(600_000L);
        if (wf == null) {
            emitter.completeWithError(new IllegalArgumentException("Unknown workflow: " + id));
            return emitter;
        }

        var progress = new ai.mindconnect.workflow.execution.WorkflowEventListener() {
            private void send(String state, ai.mindconnect.workflow.execution.StepInstance<?> step, String error) {
                try {
                    var payload = WORKFLOW_MAPPER.createObjectNode()
                            .put("step", step.getConfig().getName())
                            .put("type", step.getConfig().getType())
                            .put("state", state);
                    if (error != null) payload.put("error", error);
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().name("step").data(payload.toString(),
                                    org.springframework.http.MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // Client gone — the run itself continues; the final state
                    // is still recorded/persisted as usual.
                }
            }
            @Override public void beforeStepExecute(ai.mindconnect.workflow.execution.StepInstance<?> s) { send("RUNNING", s, null); }
            @Override public void afterStepExecute(ai.mindconnect.workflow.execution.StepInstance<?> s)  { send("FINISHED", s, null); }
            @Override public void onStepExecuteError(ai.mindconnect.workflow.execution.StepInstance<?> s, Exception e) { send("ERROR", s, e.getMessage()); }
        };

        // The run is synchronous — hand it to a worker so the emitter can
        // stream while it executes.
        Thread.ofVirtual().name("wf-run-stream-" + id).start(() -> {
            try {
                WorkflowRunService.RunReport report = service.run(wf, params, progress, persist);
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                        .event().name("result").data(
                                WORKFLOW_MAPPER.writeValueAsString(toResult(report)),
                                org.springframework.http.MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "List a workflow's stored runs",
            description = "The instance IS the run record: halted runs waiting to be "
                    + "resumed, plus finished runs kept with persist=true.")
    @GetMapping("/api/workflows/{id}/instances")
    public List<InstanceInfo> instancesOf(@PathVariable String id) {
        return service.instancesOf(id).stream().map(WorkflowApiController::toInfo).toList();
    }

    // ── Instances ──────────────────────────────────────────────────────────

    @Operation(summary = "Get a run instance")
    @GetMapping("/api/workflow-instances/{instanceId}")
    public ResponseEntity<InstanceInfo> instance(@PathVariable String instanceId) {
        return service.instance(instanceId)
                .map(s -> ResponseEntity.ok(toInfo(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Resumes a halted instance with the given parameters (form values etc.). */
    @Operation(summary = "Resume a halted instance",
            description = "Continues a HALTED run with the given parameters (form values "
                    + "etc.); the instance is overwritten in place. 409 when the instance "
                    + "is finished history or its workflow definition is gone.")
    @PostMapping("/api/workflow-instances/{instanceId}/resume")
    public ResponseEntity<RunResult> resume(@PathVariable String instanceId,
                                            @RequestBody(required = false) Map<String, Object> params) {
        WorkflowInstanceSnapshot snapshot = service.instance(instanceId).orElse(null);
        if (snapshot == null) return ResponseEntity.notFound().build();
        if (WorkflowAdminService.statusOf(snapshot) != WorkflowInstanceSnapshot.Status.HALTED) {
            return ResponseEntity.status(409).build();   // finished/errored history, nothing to resume
        }
        WorkflowData wf = service.find(snapshot.getWorkflowName()).orElse(null);
        if (wf == null) return ResponseEntity.status(409).build();
        return ResponseEntity.ok(toResult(service.resume(wf, snapshot, params)));
    }

    @Operation(summary = "Delete a run instance")
    @DeleteMapping("/api/workflow-instances/{instanceId}")
    public ResponseEntity<Void> deleteInstance(@PathVariable String instanceId) {
        return service.deleteInstance(instanceId) ? ResponseEntity.noContent().build()
                                                  : ResponseEntity.notFound().build();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static RunResult toResult(WorkflowRunService.RunReport report) {
        return new RunResult(report.outcome().name(), report.instanceId(),
                report.result(), report.error());
    }

    private static InstanceInfo toInfo(WorkflowInstanceSnapshot s) {
        return new InstanceInfo(s.getInstanceId(), s.getWorkflowName(),
                WorkflowAdminService.statusOf(s).name(), s.getStartedAt(), s.getSuspendedAt());
    }
}
