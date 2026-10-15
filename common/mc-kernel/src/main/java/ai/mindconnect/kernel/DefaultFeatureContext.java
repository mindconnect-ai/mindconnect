package ai.mindconnect.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The context a builder hands every feature: one bean registry, one
 * feature registry, one property map and the lifecycle hooks, shared by all
 * features of a kernel. The builder runs {@link #start()} after the last
 * {@code configure} and {@link #close()} when the kernel closes.
 */
public class DefaultFeatureContext implements FeatureContext {

    private final Kernel kernel;
    private final DefaultBeans beans;
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<Runnable> startHooks = new ArrayList<>();
    private final List<AutoCloseable> closeHooks = new ArrayList<>();

    public DefaultFeatureContext(Kernel kernel, DefaultBeans beans) {
        this.kernel = kernel;
        this.beans = beans;
    }

    @Override
    public Kernel kernel() {
        return kernel;
    }

    @Override
    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    /** Every property published so far — the tool environment's string side. */
    public Map<String, String> properties() {
        return Map.copyOf(properties);
    }

    @Override
    public <T> void bean(Class<T> type, Supplier<? extends T> factory) {
        beans.register(type, factory);
    }

    @Override
    public <T> void decorate(Class<T> type, UnaryOperator<T> decorator) {
        beans.decorate(type, decorator);
    }

    @Override
    public <T> void contribute(Class<T> type, T item) {
        beans.contribute(type, item);
    }

    @Override
    public void property(String key, String value) {
        if (value == null) properties.remove(key); else properties.put(key, value);
    }

    @Override
    public void onStart(Runnable hook) {
        startHooks.add(hook);
    }

    @Override
    public void onClose(AutoCloseable hook) {
        closeHooks.add(hook);
    }

    /** Runs the start hooks in registration order. */
    public void start() {
        for (Runnable hook : startHooks) hook.run();
    }

    /** Runs the close hooks in reverse order; one failing does not stop the others. */
    public void close() {
        List<Exception> failures = new ArrayList<>();
        for (int i = closeHooks.size() - 1; i >= 0; i--) {
            try {
                closeHooks.get(i).close();
            } catch (Exception e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            FeatureException e = new FeatureException(failures.size() + " close hook(s) failed");
            e.initCause(failures.get(0));
            failures.stream().skip(1).forEach(e::addSuppressed);
            throw e;
        }
    }
}
