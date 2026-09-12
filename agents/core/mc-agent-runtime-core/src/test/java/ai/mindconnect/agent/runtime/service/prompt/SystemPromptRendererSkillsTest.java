package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skills an agent may load stand in the system prompt, a line each — and
 * an agent with skills switched off reads a prompt that never mentions them.
 */
class SystemPromptRendererSkillsTest {

    /** The agent's own template, rendered as it stands. */
    private static final PromptRenderer RENDERER = new PromptRenderer() {
        @Override public String render(String template, AgentDefinition def, AgentSession session,
                                       AuthenticationInfo auth, java.util.Map<String, Object> extra) {
            return template;
        }
    };

    /** Adds nothing of its own — this test is about the skills section. */
    private static final MemoryStrategy NO_MEMORY = new MemoryStrategy() {
        @Override public String kind() { return "none"; }
        @Override public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                                      AuthenticationInfo auth) { return List.of(); }
        @Override public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(
                AgentDefinition def, AgentSession session) { return List.of(); }
        @Override public String systemPromptAddendum(AgentDefinition def, AgentSession session) { return ""; }
        @Override public CompressResult compress(AgentDefinition def, AgentSession session,
                                                 AuthenticationInfo auth) { return CompressResult.empty(); }
        @Override public TokenCounter resolveTokenCounter(AgentDefinition def) { return null; }
        @Override public Integer contextWindowTokens(AgentDefinition def) { return null; }
    };

    private static final class Store implements SkillRepository {
        private final List<Skill> skills = new ArrayList<>();
        @Override public List<Skill> findAll() { return List.copyOf(skills); }
        @Override public Optional<Skill> findById(SkillId id) { return Optional.empty(); }
        @Override public Optional<Skill> findByName(String name) { return Optional.empty(); }
        @Override public Skill save(Skill skill) { skills.add(skill); return skill; }
        @Override public void deleteById(SkillId id) { }
    }

    private static String render(AgentDefinition def, SkillCatalog skills) {
        AgentSession session = AgentSession.start(def.id(), UserId.of("alice"), ConversationId.random());
        return SystemPromptRenderer.render(RENDERER, NO_MEMORY, def, session,
                AuthenticationInfo.of(session.userId()), InstructionFiles.projectOnly(), skills);
    }

    private static SkillCatalog catalogWithRelease() {
        Store store = new Store();
        store.save(Skill.create("release", "Use when cutting a release", "Tag, then push.", List.of()));
        return SkillCatalog.of(store);
    }

    private static AgentDefinition agent(AgentDefinition.SkillsConfig skills) {
        return new AgentDefinition(AgentId.random(), "main", "d", null, null,
                "You are a helpful agent.", null, "gpt", 10, null,
                ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus.ACTIVE, List.of(), List.of(),
                List.of(), null, skills, java.time.Instant.now(), java.time.Instant.now());
    }

    @Test
    void anAgentWithSkillsReadsTheirNamesAndWhenToUseThem() {
        String prompt = render(agent(AgentDefinition.SkillsConfig.all()), catalogWithRelease());

        assertThat(prompt)
                .startsWith("You are a helpful agent.")
                .contains("\n\n## Skills\n")
                .contains("- release: Use when cutting a release")
                .contains("call the `skill` tool")
                .as("the instructions arrive when the skill is loaded, not before")
                .doesNotContain("Tag, then push.");
    }

    @Test
    void anAgentWithoutSkillsReadsNothingAboutThem() {
        assertThat(render(agent(AgentDefinition.SkillsConfig.OFF), catalogWithRelease()))
                .isEqualTo("You are a helpful agent.");
        assertThat(render(agent(AgentDefinition.SkillsConfig.all()), SkillCatalog.none()))
                .as("skills on, but there are none")
                .isEqualTo("You are a helpful agent.");
    }
}
