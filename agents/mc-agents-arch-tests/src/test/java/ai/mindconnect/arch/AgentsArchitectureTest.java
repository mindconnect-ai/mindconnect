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
     * The runtime knows no conversations: a session keeps its messages in a
     * repository of its own, and conversations are a model of their own that
     * a bridge will join to sessions. The installation's store plugins and
     * the starter provide the conversation repositories for whoever else
     * uses them. Frozen with the traces, which are still filed under a
     * conversation id that is the session's.
     */
    @ArchTest
    static final ArchRule runtimeKnowsNoConversations = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("ai.mindconnect.agent..")
                    .and().resideOutsideOfPackages("ai.mindconnect.agent.runtime.infrastructure..",
                            "ai.mindconnect.agent.starter..")
                    .should().dependOnClassesThat().resideInAPackage("ai.mindconnect.message..")
                    .as("Sessions: the runtime knows no conversations"));
}
