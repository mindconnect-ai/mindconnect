package ai.mindconnect.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

/** The shared rules over every module the agents aggregator lists; see mc-arch-rules. */
@AnalyzeClasses(locations = AreaLocations.class)
class AgentsArchitectureTest {

    @ArchTest
    static final ArchTests conventions = ArchTests.in(McArchRules.class);

    /**
     * The agents know no conversations: a session keeps its messages in a
     * repository of its own, and conversations are an area of their own that
     * a bridge will join to sessions — the one place that may know both.
     */
    @ArchTest
    static final ArchRule agentsKnowNoConversations = noClasses().that().resideInAPackage("ai.mindconnect.agent..")
            .should().dependOnClassesThat().resideInAPackage("ai.mindconnect.conversation..")
            .as("Sessions: the agents know no conversations");
}
