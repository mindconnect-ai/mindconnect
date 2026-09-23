package ai.mindconnect.extension.demo;

/** The adventures' store as a bean of its own type — a generic {@code DemoStore<Adventure>} has no class to look up by. */
record AdventureStore(DemoStore<Adventure> store) {
}
