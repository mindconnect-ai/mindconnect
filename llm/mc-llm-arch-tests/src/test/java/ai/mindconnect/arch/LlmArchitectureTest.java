package ai.mindconnect.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

/** The shared rules over every module the LLM aggregator lists, and the gateway's own; see mc-arch-rules. */
@AnalyzeClasses(locations = AreaLocations.class)
class LlmArchitectureTest {

    @ArchTest
    static final ArchTests conventions = ArchTests.in(McArchRules.class);

    /** The gateway stands on its own: a library user takes it without agents or the shared libraries. */
    @ArchTest
    static final ArchRule gatewayKnowsNoOtherArea = noClasses().that().resideInAPackage("ai.mindconnect.llm..")
            .should().dependOnClassesThat(resideInAPackage("ai.mindconnect..")
                    .and(not(resideInAPackage("ai.mindconnect.llm.."))))
            .as("The LLM gateway depends on no other area");

    /** Whoever only calls a model takes the runtime API and nothing else with it. */
    @ArchTest
    static final ArchRule runtimeApiNeedsOnlyTheJdk = classes().that().resideInAPackage("ai.mindconnect.llm.runtime.api..")
            .should().onlyDependOnClassesThat(resideInAnyPackage("java..", "ai.mindconnect.llm.runtime.api.."))
            .as("The LLM runtime API depends on the JDK alone");

    /** Whoever manages models, or writes a provider or store, takes the management API and the runtime API. */
    @ArchTest
    static final ArchRule managementApiNeedsOnlyTheRuntimeApi = classes().that().resideInAPackage("ai.mindconnect.llm.management.api..")
            .should().onlyDependOnClassesThat(resideInAnyPackage("java..",
                    "ai.mindconnect.llm.runtime.api..", "ai.mindconnect.llm.management.api.."))
            .as("The LLM management API depends on the JDK and the runtime API alone");
}
