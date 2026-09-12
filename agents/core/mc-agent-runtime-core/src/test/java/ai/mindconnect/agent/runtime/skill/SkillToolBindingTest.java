package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code skill} tool is not assigned but injected: an agent with skills
 * switched on gets it, one without does not, and the names its setting
 * carries travel with the binding.
 */
class SkillToolBindingTest {

    /** No session ever activated anything — the tool list is the agent's alone. */
    private static final AgentSessionRepository NO_SESSIONS = new AgentSessionRepository() {
        @Override public AgentSession create(AgentSession session) { return session; }
        @Override public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
            return Optional.empty();
        }
        @Override public Optional<AgentSession> findById(SessionId id) { return Optional.empty(); }
        @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) { return List.of(); }
        @Override public List<AgentSession> findByUser(UserId user) { return List.of(); }
        @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
        @Override public void deleteById(SessionId id) { }
    };

    private static AgentDefinition agent(AgentDefinition.SkillsConfig skills) {
        return AgentDefinition.create("main", "d", "p", null, "gpt")
                .withTools(List.of(AgentTool.of("file_read")))
                .withSkills(skills);
    }

    /** A catalog holding one skill, so the tool is worth offering at all. */
    private static SkillCatalog catalog() {
        var store = new SkillRepository() {
            private final List<Skill> skills = List.of(
                    Skill.create("release", "Use when cutting a release", "Tag, then push.", List.of()));
            @Override public List<Skill> findAll() { return skills; }
            @Override public Optional<Skill> findById(SkillId id) { return Optional.empty(); }
            @Override public Optional<Skill> findByName(String name) { return Optional.empty(); }
            @Override public Skill save(Skill skill) { return skill; }
            @Override public void deleteById(SkillId id) { }
        };
        return SkillCatalog.of(store);
    }

    private static List<AgentTool> refs(AgentDefinition def) {
        return new DynamicToolActivations(NO_SESSIONS, catalog()).effectiveRefs(def, SessionId.random());
    }

    @Test
    void skillsOnAddsTheToolWithItsNames() {
        List<AgentTool> refs = refs(agent(new AgentDefinition.SkillsConfig(true, List.of("release"))));

        assertThat(refs).extracting(AgentTool::name).containsExactly("file_read", SkillTool.NAME);
        assertThat(refs.get(1).overrides()).containsEntry(SkillToolFactory.NAMES, List.of("release"));
    }

    @Test
    void withoutASkillToLoadTheToolIsNotOfferedAtAll() {
        var refs = new DynamicToolActivations(NO_SESSIONS, SkillCatalog.none())
                .effectiveRefs(agent(AgentDefinition.SkillsConfig.all()), SessionId.random());

        assertThat(refs).as("a switch nobody has written a skill for costs nothing")
                .extracting(AgentTool::name).containsExactly("file_read");
    }

    @Test
    void skillsOffLeavesTheToolAway() {
        assertThat(refs(agent(AgentDefinition.SkillsConfig.OFF))).extracting(AgentTool::name)
                .containsExactly("file_read");
        assertThat(refs(agent(null))).extracting(AgentTool::name).containsExactly("file_read");
    }

    /** A session repository holding one session that a search has already given {@code names}. */
    private static AgentSessionRepository sessionThatActivated(AgentSession session) {
        return new AgentSessionRepository() {
            @Override public AgentSession create(AgentSession s) { return s; }
            @Override public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
                return Optional.of(change.apply(session));
            }
            @Override public Optional<AgentSession> findById(SessionId id) { return Optional.of(session); }
            @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) { return List.of(); }
            @Override public List<AgentSession> findByUser(UserId user) { return List.of(); }
            @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
            @Override public void deleteById(SessionId id) { }
        };
    }

    @Test
    void aSkillToolFoundBySearchIsNeitherASecondToolNorAWayPastTheSetting() {
        AgentSession session = AgentSession.start(AgentId.random(), UserId.of("alice"), ConversationId.random())
                .withActivatedTools(List.of(SkillTool.NAME));
        var activations = new DynamicToolActivations(sessionThatActivated(session), catalog());

        var narrowed = activations.effectiveRefs(
                agent(new AgentDefinition.SkillsConfig(true, List.of("release"))), session.id());
        assertThat(narrowed).extracting(AgentTool::name).containsExactly("file_read", SkillTool.NAME);
        assertThat(narrowed.get(1).overrides()).containsEntry(SkillToolFactory.NAMES, List.of("release"));

        assertThat(activations.effectiveRefs(agent(AgentDefinition.SkillsConfig.OFF), session.id()))
                .as("switched off, a search result does not bring the tool back")
                .extracting(AgentTool::name).containsExactly("file_read");
    }

    @Test
    void aSkillToolAssignedByHandIsDroppedInFavourOfTheSetting() {
        AgentDefinition off = agent(AgentDefinition.SkillsConfig.OFF)
                .withTools(List.of(AgentTool.of("file_read"), AgentTool.of(SkillTool.NAME)));
        assertThat(refs(off)).extracting(AgentTool::name).containsExactly("file_read");

        var refs = refs(off.withSkills(new AgentDefinition.SkillsConfig(true, List.of("release"))));
        assertThat(refs).extracting(AgentTool::name).containsExactly("file_read", SkillTool.NAME);
        assertThat(refs.get(1).overrides()).containsEntry(SkillToolFactory.NAMES, List.of("release"));
    }

    @Test
    void theFactoryBuildsTheToolAgainstTheBindingAndTheCallersDirectory() {
        var factory = new SkillToolFactory();
        factory.bind(MapToolEnvironment.builder()
                .service(SkillCatalog.class, SkillCatalog.none())
                .build());

        assertThat(factory.isAvailable()).isTrue();
        assertThat(factory.name()).isEqualTo(SkillTool.NAME);

        AgentTool binding = AgentTool.of(SkillTool.NAME, null,
                java.util.Map.of(SkillToolFactory.NAMES, List.of("release")));
        var scope = new ToolCallScope(UserId.of("alice"), SessionId.random(), AgentId.random(),
                "/work", List.of());

        assertThat(factory.create(binding, scope)).isInstanceOf(SkillTool.class);
    }

    @Test
    void withoutACatalogTheToolIsUnavailable() {
        var factory = new SkillToolFactory();
        factory.bind(MapToolEnvironment.builder().build());

        assertThat(factory.isAvailable()).isFalse();
    }

    /** Just enough of a session to prove the working directory reaches the catalog. */
    @Test
    void theSessionsProjectSkillsAreInReach() {
        AgentSession session = AgentSession.start(AgentId.random(), UserId.of("alice"),
                ConversationId.random());
        assertThat(SkillCatalog.none().available(agent(AgentDefinition.SkillsConfig.all()), session))
                .isEmpty();
    }
}
