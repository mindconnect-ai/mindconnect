package ai.mindconnect.workflow.admin.run;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.edit.WorkflowMutator;
import ai.mindconnect.workflow.execution.LogEntry;
import ai.mindconnect.workflow.execution.StepExecutionInfo;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.execution.VariableScope;
import ai.mindconnect.workflow.execution.WorkflowContext;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowInstance;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshots;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowEventListener;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a workflow while recording each executed step instance, so the admin
 * can render the run as an ordered list. Every recorded entry keeps the name of
 * the step it came from, which is the address the editor uses — so "open step"
 * from a run still lands on the right step after the workflow has been edited.
 *
 * <p><b>Everything is captured as plain data at event time, never as a
 * reference to the live instance.</b> Two reasons, both load-bearing:
 * <ul>
 *   <li>A {@link VariableScope} is live: a step's own scope holds only what
 *       that step assigned, everything else is read through {@code parentScope},
 *       and those parents keep mutating as later steps run. Rendering a scope
 *       lazily would show every step the values from the <em>end</em> of the
 *       run — the one thing that is useless while debugging.</li>
 *   <li>A {@link StepInstance} holds the {@code WorkflowContext}, and with it
 *       the script engines. Parking instances in a run store would keep those
 *       alive for as long as the run is retained.</li>
 * </ul>
 */
public class WorkflowRunService {

    /** Read-only here: used to check whether a step name exists in the definition. */
    private static final WorkflowMutator MUTATOR = new WorkflowMutator();

    /**
     * One variable as the scope tree shows it.
     *
     * @param entries for a map or a collection, its members as their own
     *                snapshots, so the tree can nest them instead of printing
     *                one enormous line. Empty for scalars. The built-in
     *                {@code env} variable is a map of the whole process
     *                environment — inlining it made the tree unreadable.
     */
    public record VarSnapshot(String name, String type, String value, List<VarSnapshot> entries) {

        static VarSnapshot scalar(String name, String type, String value) {
            return new VarSnapshot(name, type, value, List.of());
        }

        public boolean hasEntries() {
            return entries != null && !entries.isEmpty();
        }
    }

    /**
     * A {@link VariableScope}, shaped exactly like the runtime type: the
     * variables declared <em>directly</em> in this scope, plus a link to the
     * scope it inherits from. Lookup walks that link, so the nesting is the
     * thing worth showing — a flattened name→value map would hide both the
     * level a variable lives on and any shadowing.
     *
     * @param parentScope {@code null} at the root (the workflow's own scope)
     */
    public record ScopeSnapshot(String name, List<VarSnapshot> variables, ScopeSnapshot parentScope) {}

    /** One log line emitted by a step. */
    public record LogLine(String level, String message) {}

    /**
     * One executed step, linked back to the source step by name.
     *
     * @param index        position in the run's flat list — how the UI addresses
     *                     this entry, since the same step can run many times
     *                     (once per for-each iteration)
     * @param ref          the step's name, when it is a step the editor can open.
     *                     Null for the workflow itself and for the synthetic
     *                     blocks the engine mints at runtime (a for-each spins up
     *                     one per iteration; an if runs its branch as a block) —
     *                     those exist only in the run, not in the definition.
     * @param children     what this step ran inside itself, in execution order:
     *                     a for-each's iterations, an if's chosen branch, a
     *                     block's contents
     * @param scope        the step's scope as it stood when the step finished
     * @param instanceJson the step instance's own state (config, timings, result)
     *                     — the runtime collaborators are deliberately left out
     */
    public record RunEntry(int index, String ref, String type, String name, String state,
                           String result, String error, String assignedVar,
                           long startTime, long endTime, long durationInMs,
                           List<LogLine> logs, ScopeSnapshot scope, String instanceJson,
                           List<RunEntry> children) {}

    /** What a step's event callback captured, before the tree is assembled. */
    private record Captured(String ref, String type, String name, String state,
                            String result, String error, String assignedVar,
                            long startTime, long endTime, long durationInMs,
                            List<LogLine> logs, ScopeSnapshot scope, String instanceJson) {}

    /**
     * How a run ended. {@code HALTED} is a first-class outcome, not a failure:
     * a {@code halt} step suspends the workflow on purpose, and reporting that
     * as an error is what made the old run view say "failed — null".
     */
    public enum Outcome { SUCCESS, HALTED, ERROR }

    /**
     * The outcome of a run plus the per-step trace.
     *
     * @param roots      the trace as a tree, mirroring how the steps actually
     *                   nested at runtime — a for-each's iterations and an if's
     *                   chosen branch hang under the step that ran them
     * @param steps      the same entries, flattened in execution order. The UI
     *                   addresses an entry by its index in <em>this</em> list.
     * @param scope      the {@code WorkflowInstance}'s own scope as the run left
     *                   it — the workflow-level variables, as opposed to what any
     *                   one step could see
     * @param instanceId set only when the run suspended and was written out: the
     *                   id it can be resumed by, and the only reason a halted run
     *                   is more than a dead end
     */
    public record RunReport(Outcome outcome, String result, String error,
                            List<RunEntry> roots, List<RunEntry> steps, ScopeSnapshot scope,
                            String instanceId) {
        public boolean success() { return outcome == Outcome.SUCCESS; }
    }

    /** Where a suspended run is written so it can be picked up later. May be null. */
    private final WorkflowInstanceRepository instances;

    public WorkflowRunService() {
        this(null);
    }

    public WorkflowRunService(WorkflowInstanceRepository instances) {
        this.instances = instances;
    }

    /** Runs a workflow from the start. */
    public RunReport run(WorkflowData wf, Map<String, Object> params) {
        return run(wf, params, null);
    }

    /**
     * Like {@link #run(WorkflowData, Map)}, with an additional listener that
     * observes the execution live — used for streaming progress to a UI.
     */
    public RunReport run(WorkflowData wf, Map<String, Object> params,
                         ai.mindconnect.workflow.execution.WorkflowEventListener extraListener) {
        return run(wf, params, extraListener, false);
    }

    /**
     * @param persist keep the instance as history even when the run finishes.
     *                A halted run is always written — the suspension would be
     *                unreachable otherwise.
     */
    public RunReport run(WorkflowData wf, Map<String, Object> params,
                         ai.mindconnect.workflow.execution.WorkflowEventListener extraListener,
                         boolean persist) {
        Recorder recorder = new Recorder(wf);
        // SPI factory wires every WorkflowConfigurer on the classpath — so the
        // json:, javascript:, … resolvers are all active during the run.
        WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
        WorkflowExecutorService service = new WorkflowExecutorService(factory);
        service.addEventListener(recorder);
        if (extraListener != null) {
            service.addEventListener(extraListener);
        }

        long startedAt = System.currentTimeMillis();
        try {
            return report(wf, service.executeWorkflow(wf, params == null ? Map.of() : params),
                    recorder, null, startedAt, persist);
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Picks a suspended run back up. The instance is rebuilt around a brand-new
     * context — the one it originally ran on is long gone, and the snapshot never
     * held it in the first place.
     */
    public RunReport resume(WorkflowData wf, WorkflowInstanceSnapshot snapshot,
                            Map<String, Object> params) {
        return resume(wf, snapshot, params, null);
    }

    /** Like {@link #resume(WorkflowData, WorkflowInstanceSnapshot, Map)}, with an extra listener (live views). */
    public RunReport resume(WorkflowData wf, WorkflowInstanceSnapshot snapshot,
                            Map<String, Object> params,
                            ai.mindconnect.workflow.execution.WorkflowEventListener extraListener) {
        Recorder recorder = new Recorder(wf);
        WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
        WorkflowContext context = factory.instantiate(wf.getName());
        // The listener has to go on the instance's own context: continueWorkflow
        // only builds a context for an instance that has none, and a restored one
        // arrives with the fresh one it was rebuilt on.
        context.addEventListener(recorder);
        if (extraListener != null) {
            context.addEventListener(extraListener);
        }

        try {
            WorkflowInstance restored = WorkflowInstanceSnapshots.restore(wf, snapshot, context);
            WorkflowResult result = new WorkflowExecutorService(factory)
                    .continueWorkflow(restored, params == null ? Map.of() : params);

            // The same instance is saved again with its new status — resumed, not
            // replaced — so the id (and the history in runTraceJson) stays stable
            // across any number of halts. A resumed run was necessarily kept when
            // it halted, so it stays kept when it finishes.
            return report(wf, result, recorder, snapshot,
                    snapshot.getStartedAt() > 0 ? snapshot.getStartedAt() : snapshot.getSuspendedAt(),
                    true);
        } catch (Exception e) {
            // Nothing got persisted: the suspension is untouched and can be retried.
            return failed(e);
        }
    }

    private RunReport report(WorkflowData wf, WorkflowResult result, Recorder recorder,
                             WorkflowInstanceSnapshot prior, long startedAt, boolean persist) {
        Outcome outcome = result.isSuccess() ? Outcome.SUCCESS
                : result.isHalted() ? Outcome.HALTED
                : Outcome.ERROR;
        Object value = result.getResult();
        Trace trace = assemble(result.getInstance(), recorder.captured);

        // A resume only recorded its own segment; the segments before the halt
        // travel with the instance. Prepending them makes every report the FULL
        // run, from the first step of the first segment.
        if (prior != null && prior.getRunTraceJson() != null && !prior.getRunTraceJson().isBlank()) {
            trace = prepend(readTrace(prior.getRunTraceJson()), trace);
        }

        RunReport report = new RunReport(outcome,
                value == null ? null : String.valueOf(value),
                outcome == Outcome.ERROR ? message(result.getError()) : null,
                trace.roots(),
                trace.flat(),
                // The workflow instance's own scope, snapshotted once the run is
                // over — for the instance (unlike a step) "as it ended" is exactly
                // the state worth showing.
                result.getInstance() == null ? null : scopeOf(result.getInstance()),
                prior == null ? null : prior.getInstanceId());

        // The instance IS the run record: written whenever the run halts (a
        // halted run that is not written down is just a run that stopped), and
        // for finished runs whenever the caller wants history kept.
        boolean keep = outcome == Outcome.HALTED || persist || prior != null;
        if (instances != null && result.getInstance() != null && keep) {
            String instanceId = instances.save(
                    snapshotFor(wf, outcome, result, report, prior, startedAt));
            return new RunReport(report.outcome(), report.result(), report.error(),
                    report.roots(), report.steps(), report.scope(), instanceId);
        }
        return report;
    }

    /**
     * The instance as it goes to the store: for a halt the captured resume chain,
     * for a finished run a frame-less record — same id as before when resuming,
     * status and full trace always.
     */
    private static WorkflowInstanceSnapshot snapshotFor(WorkflowData wf, Outcome outcome,
                                                        WorkflowResult result, RunReport report,
                                                        WorkflowInstanceSnapshot prior,
                                                        long startedAt) {
        long now = System.currentTimeMillis();
        WorkflowInstanceSnapshot snap = outcome == Outcome.HALTED
                ? WorkflowInstanceSnapshots.capture(result.getInstance(), now)
                : new WorkflowInstanceSnapshot();
        if (outcome != Outcome.HALTED) {
            snap.setWorkflowName(wf.getName());
            snap.setSuspendedAt(now);
        }
        snap.setInstanceId(prior == null ? null : prior.getInstanceId());
        snap.setStartedAt(startedAt > 0 ? startedAt : now);
        snap.setStatus(switch (outcome) {
            case SUCCESS -> WorkflowInstanceSnapshot.Status.FINISHED;
            case HALTED -> WorkflowInstanceSnapshot.Status.HALTED;
            case ERROR -> WorkflowInstanceSnapshot.Status.ERROR;
        });
        snap.setRunTraceJson(writeTrace(report));
        return snap;
    }

    private RunReport failed(Exception e) {
        return new RunReport(Outcome.ERROR, null, message(e), List.of(), List.of(), null, null);
    }

    // -----------------------------------------------------------------------
    // The trace as it travels with the instance
    // -----------------------------------------------------------------------

    /**
     * Plain records in, plain records out — a dedicated mapper so the trace
     * format does not drift with whatever modules the application's mapper
     * happens to carry.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The run a stored instance describes. This is how history is read back:
     * the report renders exactly like the run that produced it. An instance
     * from before traces existed yields an empty trace with the right outcome —
     * still resumable, just without the step list.
     */
    public static RunReport traceReport(WorkflowInstanceSnapshot snap) {
        WorkflowInstanceSnapshot.Status status =
                snap.getStatus() == null ? WorkflowInstanceSnapshot.Status.HALTED : snap.getStatus();
        Outcome outcome = switch (status) {
            case FINISHED -> Outcome.SUCCESS;
            case HALTED -> Outcome.HALTED;
            case ERROR -> Outcome.ERROR;
        };
        RunReport stored = snap.getRunTraceJson() == null || snap.getRunTraceJson().isBlank()
                ? new RunReport(outcome, null, null, List.of(), List.of(), null, null)
                : readReport(snap.getRunTraceJson());
        return new RunReport(stored.outcome(), stored.result(), stored.error(),
                stored.roots(), stored.steps(), stored.scope(), snap.getInstanceId());
    }

    private static String writeTrace(RunReport report) {
        try {
            // Without the id: the store may not have assigned one yet, and the
            // reader takes it from the snapshot it came out of anyway.
            return TRACE_MAPPER.writeValueAsString(new RunReport(report.outcome(),
                    report.result(), report.error(), report.roots(), report.steps(),
                    report.scope(), null));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the run trace", e);
        }
    }

    private static RunReport readReport(String json) {
        try {
            return TRACE_MAPPER.readValue(json, RunReport.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot read a stored run trace", e);
        }
    }

    private static Trace readTrace(String json) {
        RunReport stored = readReport(json);
        return new Trace(stored.roots(), stored.steps());
    }

    /**
     * Earlier segments first, then this one, with the flat indexes renumbered
     * over the whole — they are how the UI addresses an entry, so they must be
     * unique across segments, not per segment.
     */
    private static Trace prepend(Trace earlier, Trace current) {
        List<RunEntry> flat = new ArrayList<>();
        List<RunEntry> roots = new ArrayList<>();
        for (RunEntry root : earlier.roots()) {
            roots.add(renumber(root, flat));
        }
        for (RunEntry root : current.roots()) {
            roots.add(renumber(root, flat));
        }
        return new Trace(List.copyOf(roots), List.copyOf(flat));
    }

    /** Rebuilds an entry tree with fresh flat indexes, parent before children — same order {@code build} uses. */
    private static RunEntry renumber(RunEntry e, List<RunEntry> flat) {
        int index = flat.size();
        flat.add(null);
        List<RunEntry> children = new ArrayList<>();
        for (RunEntry child : e.children()) {
            children.add(renumber(child, flat));
        }
        RunEntry self = new RunEntry(index, e.ref(), e.type(), e.name(), e.state(),
                e.result(), e.error(), e.assignedVar(), e.startTime(), e.endTime(),
                e.durationInMs(), e.logs(), e.scope(), e.instanceJson(), List.copyOf(children));
        flat.set(index, self);
        return self;
    }

    /**
     * Snapshots each step inside its own event callback, keyed by instance
     * identity — the tree is assembled afterwards, but the contents have to be
     * taken while the step is finishing (see the class comment). Synchronized
     * because a parallel for-each fires its listeners from worker threads.
     */
    private class Recorder implements WorkflowEventListener {
        private final WorkflowData wf;
        private final Map<StepInstance<?>, Captured> captured =
                Collections.synchronizedMap(new IdentityHashMap<>());

        Recorder(WorkflowData wf) {
            this.wf = wf;
        }

        @Override
        public void afterStepExecute(StepInstance<?> instance) {
            captured.put(instance, capture(wf, instance, null));
        }

        @Override
        public void onStepExecuteError(StepInstance<?> instance, Exception e) {
            captured.put(instance, capture(wf, instance, e));
        }
    }

    // -----------------------------------------------------------------------
    // Assembling the tree
    // -----------------------------------------------------------------------

    private record Trace(List<RunEntry> roots, List<RunEntry> flat) {}

    /**
     * Rebuilds the run as a tree by walking the finished instance graph from the
     * workflow down through {@code getChildInstances()}, pairing each instance
     * with what its callback captured.
     *
     * <p>Anything captured but not reachable from the graph is appended at the
     * end rather than dropped: a trace that quietly loses steps is worse than an
     * ugly one, and it would hide exactly the kind of engine gap this walk exists
     * to expose.
     */
    private static Trace assemble(WorkflowInstance root, Map<StepInstance<?>, Captured> captured) {
        List<RunEntry> flat = new ArrayList<>();
        List<RunEntry> roots = new ArrayList<>();
        Set<StepInstance<?>> placed = Collections.newSetFromMap(new IdentityHashMap<>());

        if (root != null) {
            RunEntry rootEntry = build(root, captured, flat, placed);
            if (rootEntry != null) {
                roots.add(rootEntry);
            }
        }
        synchronized (captured) {
            for (Map.Entry<StepInstance<?>, Captured> e : captured.entrySet()) {
                if (!placed.contains(e.getKey())) {
                    roots.add(entry(flat.size(), e.getValue(), List.of(), flat));
                }
            }
        }
        return new Trace(List.copyOf(roots), List.copyOf(flat));
    }

    private static RunEntry build(StepInstance<?> instance, Map<StepInstance<?>, Captured> captured,
                                  List<RunEntry> flat, Set<StepInstance<?>> placed) {
        Captured data = captured.get(instance);
        if (data == null) {
            return null; // never finished (a halt below it, say) — nothing to show
        }
        placed.add(instance);

        // Reserve this entry's index before descending, so a parent always
        // precedes its children in the flat list and the numbering reads in
        // execution order.
        int index = flat.size();
        flat.add(null);

        List<RunEntry> children = new ArrayList<>();
        for (StepInstance<?> child : instance.getChildInstances()) {
            RunEntry built = build(child, captured, flat, placed);
            if (built != null) {
                children.add(built);
            }
        }

        RunEntry self = new RunEntry(index, data.ref(), data.type(), data.name(), data.state(),
                data.result(), data.error(), data.assignedVar(), data.startTime(), data.endTime(), data.durationInMs(),
                data.logs(), data.scope(), data.instanceJson(), List.copyOf(children));
        flat.set(index, self);
        return self;
    }

    private static RunEntry entry(int index, Captured data, List<RunEntry> children,
                                  List<RunEntry> flat) {
        RunEntry self = new RunEntry(index, data.ref(), data.type(), data.name(), data.state(),
                data.result(), data.error(), data.assignedVar(), data.startTime(), data.endTime(), data.durationInMs(),
                data.logs(), data.scope(), data.instanceJson(), children);
        flat.add(self);
        return self;
    }

    /** The most useful line we can show the user for a failed run. */
    private static String message(Throwable error) {
        if (error == null) return "Execution failed.";
        // Step failures wrap the real cause; the cause is what the user needs.
        Throwable root = error.getCause() != null ? error.getCause() : error;
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }

    // -----------------------------------------------------------------------
    // Snapshotting — all of this runs inside the step's own event callback
    // -----------------------------------------------------------------------

    private static Captured capture(WorkflowData wf, StepInstance<?> instance, Exception error) {
        StepData cfg = instance.getConfig();
        String name = cfg.getName() == null ? "(unnamed)" : cfg.getName();
        String state = instance.getState() == null ? "?" : instance.getState().name();
        Object res = instance.getResult();
        StepExecutionInfo info = instance.getStepExecutionInfo();
        return new Captured(
                refOf(wf, cfg),
                cfg.getType(),
                name,
                state,
                res == null ? null : String.valueOf(res),
                error == null ? null : error.getMessage(),
                assignedVarsOf(cfg),
                info == null ? 0L : info.getStartTime(),
                info == null ? 0L : info.getEndTime(),
                info == null ? 0L : info.getDurationInMs(),
                logsOf(instance),
                scopeOf(instance),
                RunInstanceJson.of(instance, error));
    }

    /**
     * The variables this step wrote its result into, or null when it wrote none.
     *
     * <p>Most steps route their result through {@code assignResultToVar}. An
     * assign-variables step does not — it names its targets in the assignments
     * themselves, so its result "landing variable" is the assignment's varName.
     * Reading both is what lets the run view say <em>where</em> the output went,
     * rather than only what it was.
     */
    private static String assignedVarsOf(StepData cfg) {
        List<String> vars = new ArrayList<>();
        if (cfg.getAssignResultToVar() != null && !cfg.getAssignResultToVar().isBlank()) {
            vars.add(cfg.getAssignResultToVar());
        }
        if (cfg instanceof ai.mindconnect.workflow.domain.AssignVariablesData a
                && a.getVariableAssignments() != null) {
            a.getVariableAssignments().forEach(va -> {
                if (va.getVarName() != null && !va.getVarName().isBlank()) {
                    vars.add(va.getVarName());
                }
            });
        }
        return vars.isEmpty() ? null : String.join(", ", vars);
    }

    /**
     * The editor address of the step this instance ran, or null when there is
     * none. Not every executed step exists in the definition: the workflow
     * container is not a step, a for-each mints a fresh block per iteration
     * ({@code loop_0}, {@code loop_1}), and an if runs its branch as a block.
     * Offering "open step" for those would send the user to a 404, so the ref is
     * only handed out when the name actually resolves in the workflow.
     */
    private static String refOf(WorkflowData wf, StepData cfg) {
        if (cfg instanceof WorkflowData || cfg.getName() == null) {
            return null;
        }
        return MUTATOR.find(wf, cfg.getName()) == null ? null : cfg.getName();
    }

    private static List<LogLine> logsOf(StepInstance<?> instance) {
        StepExecutionInfo info = instance.getStepExecutionInfo();
        if (info == null || info.getLogs() == null) return List.of();
        List<LogLine> lines = new ArrayList<>();
        for (LogEntry e : info.getLogs()) {
            lines.add(new LogLine(e.getLevel(), e.getMessage()));
        }
        return List.copyOf(lines);
    }

    /** This step's own scope, with its {@code parentScope} chain hanging off it. */
    private static ScopeSnapshot scopeOf(StepInstance<?> instance) {
        return snapshot(instance.getVariableScope());
    }

    private static ScopeSnapshot snapshot(VariableScope scope) {
        if (scope == null) return null;
        return new ScopeSnapshot(
                scope.getScopeName() == null ? "(root)" : scope.getScopeName(),
                variablesOf(scope),
                snapshot(scope.getParentScope()));
    }

    private static List<VarSnapshot> variablesOf(VariableScope scope) {
        Map<String, VariableScope.Variable> own = scope.getVariablesMap();
        if (own == null || own.isEmpty()) return List.of();
        List<VarSnapshot> out = new ArrayList<>();
        own.forEach((key, variable) -> out.add(snapshot(key, variable.getValue(), 0)));
        return List.copyOf(out);
    }

    /** How deep a nested map/list is unfolded before it is just printed. */
    private static final int MAX_DEPTH = 3;
    /** How many members of one map/list are shown before the rest is summarised. */
    private static final int MAX_ENTRIES = 100;

    /**
     * One variable, unfolded into a tree when it is a map or a collection. The
     * depth and entry caps keep a runaway value (a big HTTP response, {@code env})
     * from turning the dialog into a wall of text.
     */
    private static VarSnapshot snapshot(String name, Object value, int depth) {
        if (value == null) {
            return VarSnapshot.scalar(name, "null", "null");
        }
        String type = value.getClass().getSimpleName();

        if (depth < MAX_DEPTH && value instanceof Map<?, ?> map) {
            List<VarSnapshot> entries = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (entries.size() == MAX_ENTRIES) break;
                entries.add(snapshot(String.valueOf(e.getKey()), e.getValue(), depth + 1));
            }
            return new VarSnapshot(name, type,
                    summary(map.size(), "entr" + (map.size() == 1 ? "y" : "ies")),
                    List.copyOf(entries));
        }

        if (depth < MAX_DEPTH && value instanceof Iterable<?> iterable) {
            List<VarSnapshot> entries = new ArrayList<>();
            int i = 0;
            for (Object item : iterable) {
                if (entries.size() == MAX_ENTRIES) break;
                entries.add(snapshot("[" + (i++) + "]", item, depth + 1));
            }
            return new VarSnapshot(name, type,
                    summary(i, "item" + (i == 1 ? "" : "s")), List.copyOf(entries));
        }

        return VarSnapshot.scalar(name, type, RunInstanceJson.display(value));
    }

    private static String summary(int size, String noun) {
        String text = size + " " + noun;
        return size > MAX_ENTRIES ? text + " (first " + MAX_ENTRIES + " shown)" : text;
    }
}
