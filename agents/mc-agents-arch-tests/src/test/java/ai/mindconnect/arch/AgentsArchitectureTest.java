package ai.mindconnect.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/** The shared rules over every module the agents aggregator lists; see mc-arch-rules. */
@AnalyzeClasses(locations = AreaLocations.class)
class AgentsArchitectureTest {

    @ArchTest
    static final ArchTests conventions = ArchTests.in(McArchRules.class);

    /**
     * A session's messages are read through the runtime's SessionStore, never
     * past it: the store that keeps them in the message repository is about
     * to be replaced by one of their own. Frozen with whoever still builds the
     * old store; the store goes, and the rule with it, once nobody does.
     * Migrations read what was kept the old way: that is what they are for.
     */
    @ArchTest
    static final ArchRule sessionsAreReadThroughTheStore = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackages("ai.mindconnect.agent.runtime.store..",
                            "ai.mindconnect.agent.migration..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("ai.mindconnect.agent.runtime.store.RepositorySessionStore")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("ai.mindconnect.agent.runtime.store.SessionMessageMapper")
                    .as("Sessions: a session's messages are read through the SessionStore"));
}
