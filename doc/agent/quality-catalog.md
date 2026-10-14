# Quality catalog

The numbered rules behind the Code Conventions in `CLAUDE.md`. D-rules are
decided by the build; I-rules need judgement and are applied by the reviewer
agents and `/tidy`. A finding names its rule number.

D-rules are **frozen**: the violations that existed when a rule was switched
on are recorded (ArchUnit: `<area>/*-arch-tests/archunit_store/`, PMD:
`mc-java-parent/pmd-baseline.properties`) and tolerated; a new one fails the
build; the records may only shrink. `scripts/baseline.sh` measures the state,
`doc/agent/baseline.md` shows it.

## D — decided by the build

| Rule | Says | Tool | Where configured |
| --- | --- | --- | --- |
| **D1** | A `-core` module or an engine (`mc-workflow`, `mc-task-queue`) imports neither Spring nor Jackson databind nor JDBC. No `adapter` package inside a `-core` module. Spring wiring (`@Component`, `@Configuration`, `@RestController`, `@AutoConfiguration` …) lives in `springstarter/`, an `-app`, `-rest`, `-ui` or demo module. | ArchUnit | `common/mc-arch-rules` `McArchRules.coreKnowsNoFramework`, `noAdapterInCore`, `springWiringOnlyAtTheEdge` |
| **D2** | Dependencies point inwards: `domain` ← `port` ← `service` ← `adapter`. A service depends on ports, never on an adapter. An adapter does not import another module's adapter. | ArchUnit | `McArchRules.layers`, `adaptersStayApart` |
| **D3** | Size is a reason to look: a class over 300 lines or 80 total complexity, a method over 50 statements or complexity 12, more than 7 parameters, more than 10 fields. | PMD `NcssCount`, `CyclomaticComplexity`, `ExcessiveParameterList`, `TooManyFields`; ArchUnit `narrowConstructors` | `mc-java-parent/pmd-ruleset.xml`, `McArchRules` |
| **D4** | A name says what the thing is for: no class ending in `Helper`, `Util(s)`, `Support`, `Commons`, `Holder`, `Manager`; no method starting with `process`, `handle`, `do`; no digit suffix. | PMD `ClassNamingConventions`, `MethodNamingConventions` | `pmd-ruleset.xml` |
| **D5** | A comment says why. A comment block over 25 lines is an essay; a main source file over 30 % comment lines is listed (advisory). | PMD `CommentSize`; `.github/scripts/javadoc-ratio.sh` | `pmd-ruleset.xml`, CI |
| **D6** | Tests are fixed, not deleted: the number of `@Test` methods in a module does not fall against the base branch; no `@Disabled` without a reason. | `.github/scripts/test-count.sh` | CI (pull requests), the Stop hook |
| **D7** | An exception keeps its cause; nothing is caught and dropped: no empty catch, no `catch (Throwable)`, no catching `NullPointerException`, no reassigning the caught variable, a collection-returning method returns empty rather than null. | PMD `EmptyCatchBlock`, `AvoidCatchingThrowable`, `AvoidCatchingNPE`, `PreserveStackTrace`, `AvoidReassigningCatchVariables`, `ReturnEmptyCollectionRatherThanNull` | `pmd-ruleset.xml` |
| **D8** | One constructor per class; SLF4J everywhere, never `java.util.logging`; imports, never inline fully qualified names; no unused private members or imports. | ArchUnit `oneConstructor`, `slf4jOnly`; PMD `UnnecessaryFullyQualifiedName`, `UnnecessaryImport`, `UnusedPrivate*`, `UnusedLocalVariable` | `McArchRules`, `pmd-ruleset.xml` |
| **D9** | Line coverage per module is reported (Jacoco, `target/site/jacoco/`). No threshold yet; `-core` modules get one in a ratchet step. | Jacoco | `mc-java-parent/pom.xml`, profile `sensors` |

Deliberately not mechanical, although a tool could approximate it: whether a
name says the *right* thing (I2), whether a comment explains *why* (I3),
whether an interface is speculative (I4). A port with one implementation is
by design; a tool cannot tell it from a speculative abstraction.

## I — judged by a reviewer

The I-rules are the judgement half of the Code Conventions. They are
numbered here so a finding can cite them; the wording is the conventions'.

| Rule | Question the reviewer answers | Applied by |
| --- | --- | --- |
| **I1** | Can the class be described in one sentence without "and"? If not, it is two classes. | readability-reviewer, `/tidy` |
| **I2** | Does the name say what, not how? Is the class named after its pattern, in the singular (Registry, Resolver, Policy, Factory, Repository, Converter, Renderer …)? Is Converter two directions, Definition : Instance the configured/running pair? | readability-reviewer |
| **I3** | Does the Javadoc say why, state an invariant or a side effect, in one paragraph, present tense, with no history and no design-note references? Nothing that restates the signature? | readability-reviewer, `/tidy` |
| **I4** | Is there an abstraction with one caller, a configuration nobody asked for, error handling for a case that cannot happen, a feature that was not requested? | readability-reviewer, architecture-reviewer |
| **I5** | Is there a null check, try/catch or fallback at a place that cannot fail? | readability-reviewer, `/tidy` |
| **I6** | Was an existing pattern reused (typed id records, `NamespaceRouted`, `RuntimeFeature`, installers in the owning module, one injected `ObjectMapper`) rather than reinvented? Is a business rule in one application service, not in two controllers? Was something copied between sibling modules instead of extracted? | architecture-reviewer |
| **I7** | Was anything changed outside the SPEC's Touches? Was the change surgical, in the surrounding style, with no "improve while here"? Were findings outside the scope recorded in `doc/known-gaps.md`? | spec-reviewer |
| **I8** | Do the tests check the requirement or the implementation? Would each test fail if the behaviour were missing? Were test sources left untouched by a rename, move or split? | test-reviewer |

## Adding or sharpening a rule

The second time an agent gets something wrong, write it down; the third time,
make it mechanical if a tool can decide it. See `doc/agent/README.md` for
which file takes which kind of rule. A new D-rule is switched on frozen: run
the area's arch tests with `-Darchunit.freeze.store.default.allowStoreUpdate=true`
or regenerate `pmd-baseline.properties` from the PMD reports, commit the
records, and from then on only new violations fail.
