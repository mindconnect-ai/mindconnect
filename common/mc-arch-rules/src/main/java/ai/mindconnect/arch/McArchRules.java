package ai.mindconnect.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The lines of the code conventions (doc/agent/conventions.md) that a tool can
 * decide, as rules every area's arch-tests module includes with
 * {@code ArchTests.in(McArchRules.class)}.
 *
 * <p>Every rule is frozen: the violations of today are recorded in the area's
 * {@code archunit_store/} and tolerated, a new one fails the build, and the
 * store may only shrink. The rule text is the key into the store, so it
 * changes only together with the store.
 */
public final class McArchRules {

    private static final DescribedPredicate<JavaClass> IN_CORE_MODULE =
        McModule.inModuleWhere("in a -core module or an engine", McModule::isCore);

    private static final DescribedPredicate<JavaClass> IN_BOOT_MODULE =
        McModule.inModuleWhere("in a starter, app, rest, ui or demo module", McModule::mayCarrySpringWiring);

    private static final Set<String> SPRING_WIRING = Set.of(
        "Component", "Service", "Repository", "Controller", "RestController",
        "Configuration", "AutoConfiguration", "ControllerAdvice", "RestControllerAdvice");

    private static final String PROJECT_PACKAGE = "ai.mindconnect.";

    private static final String JSON_MAPPER_FACTORY = "ai.mindconnect.jackson.JsonMapperFactory";
    private static final String OBJECT_MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";
    private static final String JSON_MAPPER = "com.fasterxml.jackson.databind.json.JsonMapper";

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

    /**
     * D2: a module keeps things in memory, on files and in Postgres from one
     * jar, and mc-jdbc is an optional dependency of it. A class outside the
     * database packages that touched JDBC would fail at run time in an
     * installation that has no database, where no compiler says so.
     */
    @ArchTest
    static final ArchRule jdbcOnlyInDatabasePackages = FreezingArchRule.freeze(
        noClasses().that().resideOutsideOfPackages("..pg..", "..jdbc..", "..pgvector..")
            .should().dependOnClassesThat().resideInAnyPackage("ai.mindconnect.jdbc..", "java.sql..")
            .as("D2: only a pg, jdbc or pgvector package uses JDBC"));

    /** D1: Spring wiring lives in springstarter/, an -app module or a REST/UI library, not in a library. */
    @ArchTest
    static final ArchRule springWiringOnlyAtTheEdge = FreezingArchRule.freeze(
        classes().that(carrySpringWiring())
            .should(McArchRules.be(IN_BOOT_MODULE))
            .as("D1: @Component, @Configuration, @RestController ... only in starters, apps, rest/ui modules and demos"));

    /**
     * D2: packages that need each other cannot be understood, tested or moved
     * apart. One violation per package dependency that lies on a cycle, so that
     * the store shrinks edge by edge while a cycle is being cut.
     */
    @ArchTest
    static final ArchRule noPackageCycles = FreezingArchRule.freeze(
        classes().should(notLieOnAPackageCycle())
            .as("D2: no cycles between packages"));

    /**
     * D3: more than seven constructor parameters is more than one responsibility.
     * A record is exempt: its constructor is its list of fields, and a value
     * with nine fields is a value with nine fields, not a class doing nine things.
     */
    @ArchTest
    static final ArchRule narrowConstructors = FreezingArchRule.freeze(
        classes().should(haveConstructorsWithAtMostParameters(7))
            .as("D3: a constructor takes at most 7 parameters (a record is exempt)"));

    /**
     * D3: a class with more than 30 methods has more than one responsibility. A
     * record is exempt, its accessors come with its components. The message
     * carries no count, so a frozen class does not fail again when it shrinks.
     */
    @ArchTest
    static final ArchRule fewMethods = FreezingArchRule.freeze(
        classes().that().areNotRecords()
            .should(haveAtMostMethods(30))
            .as("D3: a class has at most 30 methods"));

    /**
     * D3: a class that needs more than 25 others of the project is hard to
     * change and hard to test. Nested classes count as their top-level class:
     * a mapper over one family of nested DTOs depends on one thing.
     */
    @ArchTest
    static final ArchRule lowFanOut = FreezingArchRule.freeze(
        classes().should(dependOnAtMostProjectClasses(25))
            .as("D3: a class depends on at most 25 other classes of the project"));

    /** D4: a suffix like Helper or Manager says the author did not find the role. */
    @ArchTest
    static final ArchRule classNamesSayWhat = FreezingArchRule.freeze(
        noClasses().that().areNotAnonymousClasses()
            .should().haveNameMatching(".*(Helper|Util|Utils|Support|Commons|Holder|Manager)")
            .as("D4: a class name says what the thing is for (no Helper, Util, Support, Commons, Holder, Manager)"));

    /**
     * D4: process, handle and do say nothing about what a method does. A method
     * that a supertype may name is exempt, because its name is not ours to choose;
     * a supertype outside the analysed classes (Spring's {@code OncePerRequestFilter})
     * counts as naming it, since its methods are not visible here.
     */
    @ArchTest
    static final ArchRule methodNamesSayWhat = FreezingArchRule.freeze(
        noMethods().that(nameNoSupertypeDeclares())
            .should().haveNameMatching("(process|handle|do)[A-Z].*")
            .as("D4: a method name says what it does (no process, handle, do)"));

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

    /**
     * Conventions: every ObjectMapper comes from JsonMapperFactory. A mapper
     * built by hand misses the modules the installation announces — an id is
     * then written as an object, and nothing fails.
     */
    @ArchTest
    static final ArchRule mappersFromTheFactory = FreezingArchRule.freeze(
        noClasses().that().doNotHaveFullyQualifiedName(JSON_MAPPER_FACTORY)
            .should().callCodeUnitWhere(buildAMapper())
            .as("Conventions: every ObjectMapper comes from JsonMapperFactory"));

    /**
     * Contracts: the agents' contracts know no Jackson. The ids, the scope, the
     * tool SPI, the runtime API and the loop are what a library user builds on,
     * and they are to bring no foreign jar along; how an id stands in JSON is
     * mc-agent-jackson's. Named by package, so it holds when modules merge.
     */
    @ArchTest
    static final ArchRule contractsKnowNoJackson = FreezingArchRule.freeze(
        noClasses().that().resideInAPackage("ai.mindconnect.agent")
            .or().resideInAnyPackage("ai.mindconnect.agent.scope..", "ai.mindconnect.agent.tool..",
                "ai.mindconnect.agent.runtime.api..", "ai.mindconnect.agent.loop..")
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml..")
            .as("Contracts: the agents' contracts know no Jackson"));

    /**
     * Conventions: no ObjectMapper is static. A static mapper is built before
     * any runtime exists and so knows none of the modules its plugins
     * contribute; a component gets its JsonMapperFactory through its constructor.
     */
    @ArchTest
    static final ArchRule noStaticMapper = FreezingArchRule.freeze(
        noFields().that().areStatic()
            .should().haveRawType(JavaClass.Predicates.assignableTo(OBJECT_MAPPER))
            .as("Conventions: no ObjectMapper is static"));

    private static DescribedPredicate<JavaCall<?>> buildAMapper() {
        return DescribedPredicate.describe("build an ObjectMapper themselves", call -> {
            String owner = call.getTargetOwner().getFullName();
            boolean mapperType = owner.equals(OBJECT_MAPPER) || owner.equals(JSON_MAPPER);
            return mapperType && (call instanceof JavaConstructorCall
                || owner.equals(JSON_MAPPER) && call.getName().equals("builder"));
        });
    }

    private static DescribedPredicate<JavaMethod> nameNoSupertypeDeclares() {
        return DescribedPredicate.describe("are not named by a supertype", method -> {
            JavaClass owner = method.getOwner();
            return Stream.concat(owner.getAllRawSuperclasses().stream(), owner.getAllRawInterfaces().stream())
                .noneMatch(supertype -> !supertype.isFullyImported()
                    || supertype.getMethods().stream().anyMatch(inherited -> inherited.getName().equals(method.getName())));
        });
    }

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
                if (javaClass.isRecord()) {
                    return;
                }
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

    private static ArchCondition<JavaClass> haveAtMostMethods(int max) {
        return new ArchCondition<>("have at most " + max + " methods") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                long declared = javaClass.getMethods().stream()
                    .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
                    .count();
                if (declared > max) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                        javaClass.getName() + " has more than " + max + " methods"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> dependOnAtMostProjectClasses(int max) {
        return new ArchCondition<>("depend on at most " + max + " other classes of the project") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String own = topLevelName(javaClass);
                long others = javaClass.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass().getBaseComponentType())
                    .filter(target -> target.getName().startsWith(PROJECT_PACKAGE))
                    .map(McArchRules::topLevelName)
                    .filter(target -> !target.equals(own))
                    .distinct()
                    .count();
                if (others > max) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                        javaClass.getName() + " depends on more than " + max + " other classes of the project"));
                }
            }
        };
    }

    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static ArchCondition<JavaClass> notLieOnAPackageCycle() {
        return new ArchCondition<>("not lie on a package cycle") {
            private final Map<String, Set<String>> dependencies = new TreeMap<>();

            @Override
            public void init(Collection<JavaClass> allClasses) {
                dependencies.clear();
            }

            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<String> targets = dependencies.computeIfAbsent(javaClass.getPackageName(), name -> new TreeSet<>());
                javaClass.getDirectDependenciesFromSelf().forEach(dependency ->
                    targets.add(dependency.getTargetClass().getBaseComponentType().getPackageName()));
            }

            @Override
            public void finish(ConditionEvents events) {
                dependencies.forEach((from, targets) -> targets.stream()
                    .filter(to -> !to.equals(from) && leadsTo(to, from, new HashSet<>()))
                    .forEach(to -> events.add(SimpleConditionEvent.violated(from,
                        "package " + from + " depends on " + to + ", which leads back to it"))));
            }

            private boolean leadsTo(String from, String goal, Set<String> visited) {
                if (from.equals(goal)) {
                    return true;
                }
                return visited.add(from) && dependencies.getOrDefault(from, Set.of()).stream()
                    .anyMatch(next -> leadsTo(next, goal, visited));
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
