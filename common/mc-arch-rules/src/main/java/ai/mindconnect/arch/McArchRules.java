package ai.mindconnect.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The architecture lines of the Code Conventions (CLAUDE.md) that a tool can
 * decide, as rules every area's arch-tests module includes with
 * {@code ArchTests.in(McArchRules.class)}.
 *
 * <p>Every rule is frozen: the violations of today are recorded in the area's
 * {@code archunit_store/} and tolerated, a new one fails the build, and the
 * store may only shrink (doc/agent/quality-catalog.md, rules D1–D4).
 */
public final class McArchRules {

    private static final DescribedPredicate<JavaClass> IN_CORE_MODULE =
        McModule.inModuleWhere("in a -core module or an engine", McModule::isCore);

    private static final DescribedPredicate<JavaClass> IN_BOOT_MODULE =
        McModule.inModuleWhere("in a starter, app, rest, ui or demo module", McModule::mayCarrySpringWiring);

    private static final Set<String> SPRING_WIRING = Set.of(
        "Component", "Service", "Repository", "Controller", "RestController",
        "Configuration", "AutoConfiguration", "ControllerAdvice", "RestControllerAdvice");

    private McArchRules() {
    }

    /** D1: a -core module or an engine imports neither Spring nor Jackson databind nor JDBC. */
    @ArchTest
    static final ArchRule coreKnowsNoFramework = FreezingArchRule.freeze(
        noClasses().that(IN_CORE_MODULE)
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                "java.sql..", "javax.sql..", "com.fasterxml.jackson.databind..", "org.hibernate..")
            .as("D1: -core modules and engines know no framework"));

    /** D1: no adapter package inside a -core module. */
    @ArchTest
    static final ArchRule noAdapterInCore = FreezingArchRule.freeze(
        noClasses().that(IN_CORE_MODULE)
            .should().resideInAPackage("..adapter..")
            .as("D1: a -core module holds domain and ports, adapters live in the sibling"));

    /** D2: dependencies point inwards; a service depends on ports, never on an adapter. */
    @ArchTest
    static final ArchRule layers = FreezingArchRule.freeze(
        layeredArchitecture().consideringOnlyDependenciesInLayers().withOptionalLayers(true)
            .layer("Domain").definedBy("..domain..")
            .layer("Port").definedBy("..port..")
            .layer("Service").definedBy("..service..")
            .layer("Adapter").definedBy("..adapter..")
            .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Adapter")
            .whereLayer("Port").mayOnlyBeAccessedByLayers("Service", "Adapter")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Port", "Service", "Adapter")
            .as("D2: domain <- port <- service <- adapter"));

    /** D2: no adapter imports the adapter of another module. */
    @ArchTest
    static final ArchRule adaptersStayApart = FreezingArchRule.freeze(
        classes().that().resideInAPackage("..adapter..")
            .should(notDependOnAdaptersOfOtherModules())
            .as("D2: an adapter does not import another module's adapter"));

    /** D1: Spring wiring lives in springstarter/, an -app module or a REST/UI library, not in a library. */
    @ArchTest
    static final ArchRule springWiringOnlyAtTheEdge = FreezingArchRule.freeze(
        classes().that(carrySpringWiring())
            .should(McArchRules.be(IN_BOOT_MODULE))
            .as("D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos"));

    /** D3: more than seven constructor parameters is more than one responsibility. */
    @ArchTest
    static final ArchRule narrowConstructors = FreezingArchRule.freeze(
        classes().should(haveConstructorsWithAtMostParameters(7))
            .as("D3: a constructor takes at most 7 parameters"));

    /** Conventions: one constructor per class; a second one needs a real second caller. */
    @ArchTest
    static final ArchRule oneConstructor = FreezingArchRule.freeze(
        classes().that().areNotEnums().and().areNotAnonymousClasses().and().areNotInterfaces()
            .should(haveAtMostOneConstructor())
            .as("Conventions: one constructor per class"));

    /** Conventions: SLF4J everywhere, never java.util.logging. */
    @ArchTest
    static final ArchRule slf4jOnly = FreezingArchRule.freeze(
        noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..")
            .as("Conventions: SLF4J everywhere, never java.util.logging"));

    private static DescribedPredicate<JavaClass> carrySpringWiring() {
        return DescribedPredicate.describe("annotated with Spring wiring",
            javaClass -> javaClass.getAnnotations().stream().anyMatch(annotation -> {
                JavaClass type = annotation.getRawType();
                return type.getPackageName().startsWith("org.springframework")
                    && SPRING_WIRING.contains(type.getSimpleName());
            }));
    }

    private static ArchCondition<JavaClass> be(DescribedPredicate<JavaClass> predicate) {
        return new ArchCondition<>("be " + predicate.getDescription()) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!predicate.test(javaClass)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                        javaClass.getName() + " is not " + predicate.getDescription()
                            + " (module " + McModule.of(javaClass).orElse("?") + ")"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDependOnAdaptersOfOtherModules() {
        return new ArchCondition<>("not depend on adapters of other modules") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Optional<String> own = McModule.of(javaClass);
                if (own.isEmpty()) {
                    return;
                }
                javaClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.getPackageName().contains(".adapter")) {
                        return;
                    }
                    Optional<String> other = McModule.of(target);
                    if (other.isPresent() && !other.equals(own)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                            dependency.getDescription() + " (" + own.get() + " -> " + other.get() + ")"));
                    }
                });
            }
        };
    }

    private static ArchCondition<JavaClass> haveConstructorsWithAtMostParameters(int max) {
        return new ArchCondition<>("have constructors with at most " + max + " parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaConstructor constructor : javaClass.getConstructors()) {
                    int count = constructor.getRawParameterTypes().size();
                    if (count > max) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                            constructor.getFullName() + " takes " + count + " parameters"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAtMostOneConstructor() {
        return new ArchCondition<>("have at most one constructor") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long declared = javaClass.getConstructors().stream()
                    .filter(constructor -> !constructor.getModifiers().contains(JavaModifier.SYNTHETIC))
                    .count();
                if (declared > 1) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                        javaClass.getName() + " has " + declared + " constructors"));
                }
            }
        };
    }
}
