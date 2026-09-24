package ai.mindconnect.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Work that has to happen in a namespace once, the first time the namespace
 * is used after start — and never again in this process, so what every later
 * request pays is one lookup.
 *
 * <p>Registered with {@link ThreadBoundScope#onBind}, it runs on the thread
 * that entered the namespace, with the namespace already bound, before that
 * thread's own work: a request, a queued task, a start-up routine alike. A
 * second thread entering the same namespace meanwhile waits for it to finish,
 * so nobody works in a namespace that is half prepared. Work that itself
 * binds the namespace again (a nested {@code runIn}) does not start over.
 *
 * <p>The work reports its own failures; one it throws is logged, and the
 * namespace counts as done all the same — retrying on every request would
 * turn one broken seed into a slow installation. {@link #forget} and
 * {@link #forgetAll} let the next use run it again, for when what the work
 * depends on has changed.
 */
public final class NamespaceFirstUse implements Consumer<Scope> {

    private static final System.Logger log = System.getLogger(NamespaceFirstUse.class.getName());

    private final String name;
    private final Consumer<Namespace> work;
    private final Set<Namespace> done = ConcurrentHashMap.newKeySet();
    private final Map<Namespace, Object> locks = new ConcurrentHashMap<>();
    /** Namespaces whose work runs on this thread right now — a nested binding must not start it again. */
    private final ThreadLocal<Set<Namespace>> running = ThreadLocal.withInitial(java.util.HashSet::new);

    /**
     * @param name what the work is, for the log
     * @param work runs in the namespace it is given, with that namespace bound
     */
    public NamespaceFirstUse(String name, Consumer<Namespace> work) {
        this.name = Objects.requireNonNull(name, "name");
        this.work = Objects.requireNonNull(work, "work");
    }

    @Override
    public void accept(Scope scope) {
        ensure(scope.namespace());
    }

    /**
     * Runs the work for {@code namespace} unless it ran already in this process.
     * The caller has {@code namespace} bound.
     *
     * @return whether this call ran it
     */
    public boolean ensure(Namespace namespace) {
        if (done.contains(namespace)) return false;
        synchronized (locks.computeIfAbsent(namespace, ns -> new Object())) {
            if (done.contains(namespace) || running.get().contains(namespace)) return false;
            running.get().add(namespace);
            try {
                work.accept(namespace);
            } catch (RuntimeException e) {
                log.log(System.Logger.Level.WARNING,
                        "{0} failed in namespace ''{1}'': {2}", name, namespace.value(), e.toString());
            } finally {
                running.get().remove(namespace);
                done.add(namespace);
            }
            return true;
        }
    }

    /** Whether the work ran for {@code namespace} in this process. */
    public boolean isDone(Namespace namespace) {
        return done.contains(namespace);
    }

    /** The next use of {@code namespace} runs the work again. */
    public void forget(Namespace namespace) {
        done.remove(namespace);
    }

    /** The next use of every namespace runs the work again. */
    public void forgetAll() {
        done.clear();
    }

    @Override
    public String toString() {
        return "NamespaceFirstUse[" + name + ", " + done.size() + " done]";
    }
}
