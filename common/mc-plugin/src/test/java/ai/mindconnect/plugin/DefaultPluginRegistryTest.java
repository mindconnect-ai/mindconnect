package ai.mindconnect.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plugins, what they share, and the order hooks run in. */
class DefaultPluginRegistryTest {

    /** A point that upper-cases nothing by itself: text in, text out. */
    private static final HookPoint<String, String> GREET = HookPoint.of("test.greet");

    private interface Greeter {
        String greet(String name);
    }

    private interface Clock {
        long now();
    }

    /** A plugin written inline: a name and what it does when installed. */
    private static Plugin plugin(String name, Consumer<PluginRegistry> install) {
        return new Plugin() {
            @Override public String name() {
                return name;
            }

            @Override public void install(PluginRegistry registry) {
                install.accept(registry);
            }
        };
    }

    private final DefaultPluginRegistry registry = new DefaultPluginRegistry();
    private final List<String> trace = new ArrayList<>();

    private String greet(String name) {
        return registry.run(GREET, name, input -> {
            trace.add("work:" + input);
            return "Hello " + input;
        });
    }

    // ── services ────────────────────────────────────────────────────────────

    @Test
    void a_provided_service_is_built_once_and_only_when_someone_asks() {
        AtomicInteger built = new AtomicInteger();
        registry.provide(Greeter.class, () -> {
            built.incrementAndGet();
            return name -> "Hi " + name;
        });
        assertThat(built).hasValue(0);

        Greeter first = registry.get(Greeter.class);
        Greeter second = registry.get(Greeter.class);

        assertThat(first).isSameAs(second);
        assertThat(built).hasValue(1);
        assertThat(first.greet("Ada")).isEqualTo("Hi Ada");
    }

    @Test
    void providing_a_service_again_replaces_it() {
        registry.provide(Greeter.class, () -> name -> "Hi " + name);
        registry.get(Greeter.class);

        registry.provide(Greeter.class, () -> name -> "Moin " + name);

        assertThat(registry.get(Greeter.class).greet("Ada")).isEqualTo("Moin Ada");
    }

    @Test
    void a_service_nobody_provides_is_an_error_to_get_and_empty_to_find() {
        assertThat(registry.find(Greeter.class)).isEmpty();
        assertThatThrownBy(() -> registry.get(Greeter.class))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Greeter");
    }

    @Test
    void a_supplier_finds_a_service_that_is_provided_after_it_was_handed_out() {
        Supplier<Greeter> greeter = registry.supplier(Greeter.class);

        registry.provide(Greeter.class, () -> name -> "Hi " + name);

        assertThat(greeter.get().greet("Ada")).isEqualTo("Hi Ada");
    }

    @Test
    void a_supplier_follows_a_service_that_is_replaced() {
        registry.provide(Greeter.class, () -> name -> "Hi " + name);
        Supplier<Greeter> greeter = registry.supplier(Greeter.class);
        assertThat(greeter.get().greet("Ada")).isEqualTo("Hi Ada");

        registry.provide(Greeter.class, () -> name -> "Moin " + name);

        assertThat(greeter.get().greet("Ada")).isEqualTo("Moin Ada");
    }

    // ── contributions ───────────────────────────────────────────────────────

    @Test
    void every_plugins_contribution_is_kept_in_the_order_contributed() {
        registry.install(plugin("first", r -> r.contribute(Greeter.class, name -> "Hi " + name)));
        registry.install(plugin("second", r -> r.contribute(Greeter.class, name -> "Moin " + name)));

        assertThat(registry.all(Greeter.class)).extracting(greeter -> greeter.greet("Ada"))
                .containsExactly("Hi Ada", "Moin Ada");
    }

    @Test
    void a_type_nobody_contributed_to_has_no_contributions() {
        assertThat(registry.all(Greeter.class)).isEmpty();
    }

    @Test
    void contributions_and_the_service_of_a_type_are_kept_apart() {
        registry.provide(Greeter.class, () -> name -> "the one " + name);
        registry.contribute(Greeter.class, name -> "one of many " + name);

        assertThat(registry.get(Greeter.class).greet("Ada")).isEqualTo("the one Ada");
        assertThat(registry.all(Greeter.class)).hasSize(1);
    }

    // ── hooks ───────────────────────────────────────────────────────────────

    @Test
    void a_point_without_hooks_just_does_its_work() {
        assertThat(greet("Ada")).isEqualTo("Hello Ada");
    }

    @Test
    void a_before_hook_changes_what_goes_into_the_work() {
        registry.before(GREET, name -> BeforeOutcome.proceed(name.toUpperCase()));

        assertThat(greet("Ada")).isEqualTo("Hello ADA");
    }

    @Test
    void a_before_hook_that_stops_ends_the_run_and_the_work_never_happens() {
        registry.before(GREET, name -> BeforeOutcome.stop("Not you, " + name));
        registry.before(GREET, name -> {
            trace.add("second before");
            return BeforeOutcome.proceed(name);
        });

        assertThat(greet("Ada")).isEqualTo("Not you, Ada");
        assertThat(trace).isEmpty();
    }

    @Test
    void before_hooks_run_in_the_order_registered_each_on_what_the_one_before_let_pass() {
        registry.before(GREET, name -> BeforeOutcome.proceed(name + "1"));
        registry.before(GREET, name -> BeforeOutcome.proceed(name + "2"));

        assertThat(greet("Ada")).isEqualTo("Hello Ada12");
    }

    @Test
    void an_after_hook_changes_what_comes_out() {
        registry.after(GREET, (name, greeting) -> greeting + "!");

        assertThat(greet("Ada")).isEqualTo("Hello Ada!");
    }

    @Test
    void after_hooks_run_in_reverse_order_so_who_came_first_before_comes_last_after() {
        registry.after(GREET, (name, greeting) -> greeting + " [first]");
        registry.after(GREET, (name, greeting) -> greeting + " [second]");

        assertThat(greet("Ada")).isEqualTo("Hello Ada [second] [first]");
    }

    @Test
    void an_after_hook_is_not_called_for_a_run_that_was_stopped() {
        registry.before(GREET, name -> BeforeOutcome.stop("Not you"));
        registry.after(GREET, (name, greeting) -> greeting + "!");

        assertThat(greet("Ada")).isEqualTo("Not you");
    }

    @Test
    void an_after_hook_sees_the_context_as_the_before_hooks_left_it() {
        registry.before(GREET, name -> BeforeOutcome.proceed(name.toUpperCase()));
        registry.after(GREET, (name, greeting) -> greeting + " (for " + name + ")");

        assertThat(greet("Ada")).isEqualTo("Hello ADA (for ADA)");
    }

    @Test
    void an_around_hook_wraps_the_work_and_not_the_hooks_before_and_after_it() {
        registry.before(GREET, name -> {
            trace.add("before");
            return BeforeOutcome.proceed(name);
        });
        registry.around(GREET, (name, next) -> {
            trace.add("around in");
            String greeting = next.apply(name);
            trace.add("around out");
            return greeting;
        });
        registry.after(GREET, (name, greeting) -> {
            trace.add("after");
            return greeting;
        });

        greet("Ada");

        assertThat(trace).containsExactly("before", "around in", "work:Ada", "around out", "after");
    }

    @Test
    void around_hooks_nest_with_the_first_registered_outermost() {
        registry.around(GREET, (name, next) -> "[" + next.apply(name) + "]");
        registry.around(GREET, (name, next) -> "(" + next.apply(name) + ")");

        assertThat(greet("Ada")).isEqualTo("[(Hello Ada)]");
    }

    @Test
    void an_around_hook_that_does_not_call_on_replaces_the_work() {
        registry.around(GREET, (name, next) -> "cached greeting");

        assertThat(greet("Ada")).isEqualTo("cached greeting");
        assertThat(trace).isEmpty();
    }

    @Test
    void an_around_hook_can_do_the_work_again() {
        registry.around(GREET, (name, next) -> {
            next.apply(name);
            return next.apply(name);
        });

        greet("Ada");

        assertThat(trace).containsExactly("work:Ada", "work:Ada");
    }

    @Test
    void a_listener_is_told_how_the_run_ended() {
        registry.after(GREET, (name, greeting) -> greeting + "!");
        registry.listen(GREET, (name, greeting) -> trace.add(name + " -> " + greeting));

        greet("Ada");

        assertThat(trace).containsExactly("work:Ada", "Ada -> Hello Ada!");
    }

    @Test
    void a_listener_is_also_told_of_a_run_that_was_stopped() {
        registry.before(GREET, name -> BeforeOutcome.stop("Not you"));
        registry.listen(GREET, (name, greeting) -> trace.add(name + " -> " + greeting));

        greet("Ada");

        assertThat(trace).containsExactly("Ada -> Not you");
    }

    @Test
    void a_failing_listener_breaks_neither_the_run_nor_the_listeners_after_it() {
        registry.listen(GREET, (name, greeting) -> {
            throw new IllegalStateException("log is full");
        });
        registry.listen(GREET, (name, greeting) -> trace.add("second listener"));

        assertThat(greet("Ada")).isEqualTo("Hello Ada");
        assertThat(trace).containsExactly("work:Ada", "second listener");
    }

    @Test
    void a_failing_hook_fails_the_run() {
        registry.before(GREET, name -> {
            throw new IllegalStateException("policy is down");
        });

        assertThatThrownBy(() -> greet("Ada")).isInstanceOf(IllegalStateException.class).hasMessage("policy is down");
    }

    @Test
    void hooks_of_one_point_do_not_run_at_another() {
        HookPoint<String, String> other = HookPoint.of("test.other");
        registry.before(other, name -> BeforeOutcome.stop("wrong point"));

        assertThat(greet("Ada")).isEqualTo("Hello Ada");
    }

    // ── plugins and lifecycle ───────────────────────────────────────────────

    @Test
    void installing_a_plugin_lets_it_provide_and_hook() {
        registry.install(plugin("polite", r -> {
            r.provide(Greeter.class, () -> name -> "Good day, " + name);
            r.after(GREET, (name, greeting) -> greeting + ", pleased to meet you");
        }));

        assertThat(registry.get(Greeter.class).greet("Ada")).isEqualTo("Good day, Ada");
        assertThat(greet("Ada")).isEqualTo("Hello Ada, pleased to meet you");
    }

    @Test
    void a_plugin_whose_dependency_is_not_installed_first_is_refused() {
        class Base implements Plugin {
            @Override public String name() {
                return "base";
            }

            @Override public void install(PluginRegistry registry) {
            }
        }
        Plugin needsBase = new Plugin() {
            @Override public String name() {
                return "extension";
            }

            @Override public Set<Class<? extends Plugin>> dependsOn() {
                return Set.of(Base.class);
            }

            @Override public void install(PluginRegistry registry) {
            }
        };

        assertThatThrownBy(() -> registry.install(needsBase))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("extension needs Base");

        registry.install(new Base()).install(needsBase);
        assertThat(registry.plugin(Base.class).name()).isEqualTo("base");
    }

    @Test
    void starting_fails_and_names_every_service_a_plugin_requires_and_nobody_provides() {
        registry.install(new Plugin() {
            @Override public String name() {
                return "greeting";
            }

            @Override public Set<Class<?>> requires() {
                return Set.of(Greeter.class, Clock.class);
            }

            @Override public void install(PluginRegistry registry) {
            }
        });

        assertThatThrownBy(registry::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greeting needs Greeter").hasMessageContaining("greeting needs Clock");
    }

    @Test
    void a_required_service_may_be_provided_by_a_plugin_installed_later() {
        List<String> started = new ArrayList<>();
        registry.install(new Plugin() {
            @Override public String name() {
                return "greeting";
            }

            @Override public Set<Class<?>> requires() {
                return Set.of(Greeter.class);
            }

            @Override public void install(PluginRegistry registry) {
            }

            @Override public void start() {
                started.add("greeting");
            }
        });
        registry.install(plugin("greeter", r -> r.provide(Greeter.class, () -> name -> "Hi " + name)));

        registry.start();

        assertThat(started).containsExactly("greeting");
    }

    @Test
    void starting_fails_when_a_plugin_the_host_requires_is_missing() {
        class Needed implements Plugin {
            @Override public String name() {
                return "needed";
            }

            @Override public void install(PluginRegistry registry) {
            }
        }
        registry.require(Needed.class);

        assertThatThrownBy(registry::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("Needed");
    }

    @Test
    void plugins_start_in_the_order_installed_and_close_in_reverse() {
        List<String> lifecycle = new ArrayList<>();
        for (String name : List.of("one", "two")) {
            registry.install(new Plugin() {
                @Override public String name() {
                    return name;
                }

                @Override public void install(PluginRegistry registry) {
                }

                @Override public void start() {
                    lifecycle.add("start " + name);
                }

                @Override public void close() {
                    lifecycle.add("close " + name);
                }
            });
        }

        registry.start();
        registry.close();

        assertThat(lifecycle).containsExactly("start one", "start two", "close two", "close one");
    }

    @Test
    void a_plugin_that_fails_to_close_does_not_keep_the_others_open() {
        List<String> closed = new ArrayList<>();
        registry.install(new Plugin() {
            @Override public String name() {
                return "tidy";
            }

            @Override public void install(PluginRegistry registry) {
            }

            @Override public void close() {
                closed.add("tidy");
            }
        });
        registry.install(new Plugin() {
            @Override public String name() {
                return "stuck";
            }

            @Override public void install(PluginRegistry registry) {
            }

            @Override public void close() {
                throw new IllegalStateException("cannot close");
            }
        });

        registry.close();

        assertThat(closed).containsExactly("tidy");
    }

    @Test
    void a_plugin_cannot_be_installed_once_the_registry_has_started() {
        registry.start();

        assertThatThrownBy(() -> registry.install(plugin("late", r -> { })))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("late");
    }
}
