package ai.mindconnect.extension.demo;

import java.util.List;

/** The scenarios' store as a bean of its own type; seeds the shipped scenarios into an empty namespace. */
record ScenarioStore(DemoStore<Scenario> store) {

    /** Every scenario of the namespace, the shipped three first if there were none. */
    List<Scenario> all() {
        List<Scenario> found = store.all();
        if (!found.isEmpty()) return found;
        Scenario.SEED.forEach(store::save);
        return store.all();
    }
}
