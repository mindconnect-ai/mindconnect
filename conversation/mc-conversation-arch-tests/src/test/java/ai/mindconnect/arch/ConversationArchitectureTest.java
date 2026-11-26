package ai.mindconnect.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

/** The shared rules over every module the conversation aggregator lists; see mc-arch-rules. */
@AnalyzeClasses(locations = AreaLocations.class)
class ConversationArchitectureTest {

    @ArchTest
    static final ArchTests conventions = ArchTests.in(McArchRules.class);

    /**
     * Conversations know no agents: an agent is one kind of participant, and
     * agents will depend on conversations, never the other way round. The
     * namespace comes from the shared scope in common.
     */
    @ArchTest
    static final ArchRule conversationsKnowNoAgents = noClasses().that().resideInAPackage("ai.mindconnect.conversation..")
            .should().dependOnClassesThat().resideInAPackage("ai.mindconnect.agent..")
            .as("Conversations know no agents");
}
