package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.toolresult.ToolResultPlugin;
import ai.mindconnect.adminui.ui.controller.AgentUiController;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.agent.skill.domain.Skill;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;

import java.util.List;

/**
 * Edit form for an agent definition. Used both for "new" (null agent)
 * and "edit" (existing agent) modes — defaults switch on the null
 * check, the submit target becomes POST vs PUT.
 *
 * <p>The form pulls the LLM configs from their repository at render time
 * and is handed every agent there is, for the group names and for the
 * candidates of the roster and the reviewers.
 */
public final class AgentFormComponent implements UiComponent {

    private final ManagedAgent agent;
    /** What the runtime runs; null with {@link #agent}. */
    private final AgentDefinition definition;
    private final LlmConfigRepository llmConfigRepository;
    private final List<ManagedAgent> all;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** What the skills picker offers; null when this host wires no skill store. */
    private final SkillRepository skills;

    /**
     * @param agent {@code null} for the new-agent form, an existing
     *              agent for the edit form
     * @param all   every agent there is, the edited one included
     */
    public AgentFormComponent(ManagedAgent agent,
                              LlmConfigRepository llmConfigRepository,
                              List<ManagedAgent> all,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(agent, llmConfigRepository, all, objectMapper, null);
    }

    public AgentFormComponent(ManagedAgent agent,
                              LlmConfigRepository llmConfigRepository,
                              List<ManagedAgent> all,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                              SkillRepository skills) {
        this.agent = agent;
        this.definition = agent == null ? null : agent.definition();
        this.llmConfigRepository = llmConfigRepository;
        this.all = all;
        this.objectMapper = objectMapper;
        this.skills = skills;
    }

    @Override
    public String id() {
        return agent == null ? "agent-new" : "agent-" + agent.id().value();
    }

    @Override
    public UiNode render() {
        boolean isNew = agent == null;

        List<UiField.Option> llmOptions = llmConfigRepository.findAll().stream()
                .map(c -> UiField.Option.of(c.name(),
                        c.name() + " (" + c.provider() + " / " + c.model() + ")"))
                .toList();

        // The rubrics that exist, read off the agents themselves — there is no
        // group registry, and inventing one for a value only the list groups by
        // would be a table to keep in sync for nothing. The seeded ones are
        // always offered so a fresh install has somewhere to file an agent —
        // the reviewer group among them, since the Response Reviewers hint
        // sends people there — and the current value is offered even if this
        // is the last agent holding it, so opening the form cannot silently
        // refile the agent.
        java.util.SortedSet<String> groupNames = new java.util.TreeSet<>(
                List.of("assistants", "sub-agents", "utilities", AgentSetting.REVIEWER_GROUP));
        all.forEach(other -> groupNames.add(AgentSetting.group(other)));
        if (!isNew) groupNames.add(AgentSetting.group(agent));
        List<UiField.Option> groupOptions = groupNames.stream()
                .map(g -> UiField.Option.of(g, ToolCatalogComponent.displayGroup(g)))
                .toList();

        // Reviewer candidates: the agents filed under the reviewer group.
        // Roster candidates: every other agent.
        List<String> reviewers = isNew ? List.of() : ResponseReviewPlugin.reviewersOf(definition);
        List<String> roster = isNew ? List.of() : SubAgentPlugin.subAgentsOf(definition);
        List<UiField.Option> reviewerOptions = candidates(
                other -> AgentSetting.REVIEWER_GROUP.equals(AgentSetting.group(other)), reviewers);
        List<UiField.Option> rosterOptions = candidates(other -> SubAgentPlugin.callable(other.definition()), roster);

        // Same bar as the list and the detail page. A form's title is a plain
        // escaped string, so the icon has to come from a header-only UiList
        // above it — and the form then goes untitled, or the name would stand
        // twice in a row. A new agent has no icon yet, so it gets the generic
        // one, which is what it will be drawn with until someone picks another.
        var header = UiList.of(id() + "-header",
                        isNew ? "New Agent" : "Edit Agent: " + definition.name())
                .icon(isNew ? AgentSetting.DEFAULT_ICON : AgentSetting.icon(agent));

        String skillsMode = isNew ? AgentSetting.ALL : AgentSetting.skillsMode(definition);
        List<String> skillNames = isNew ? List.of() : SkillPlugin.skillsOf(definition).orElse(List.of());
        var form = UiForm.of(id(), null)
                .field(UiField.text("name", "Name", isNew ? null : definition.name())
                        .asEditable().asRequired())
                // The version this form was opened with: a hidden input, submitted
                // with the rest — the save is refused if the agent was saved since.
                .field(UiField.hidden("version",
                        isNew ? "0" : Long.toString(agent.version())))
                .field(UiField.text("description", "Description", isNew ? null : definition.description())
                        .asEditable())
                // The rubric this agent is filed under in the list. A choice
                // among the rubrics in use; js/group-picker.js adds the button
                // that turns it into a field for naming a new one.
                .field(UiField.select("group", "Group",
                        isNew ? AgentSetting.DEFAULT_GROUP : AgentSetting.group(agent), groupOptions)
                        .asEditable())
                // A Lucide name. js/icon-picker.js grows a searchable grid out
                // of this field; typed by hand it works just the same.
                .field(UiField.text("icon", "Icon", isNew ? null : agent.icon())
                        .asEditable()
                        .placeholder("bot, telescope, wand-sparkles, …")
                        .hint("Shown next to the agent in the list, the chat and the history"))
                .field(UiField.textarea("systemPrompt", "System Prompt",
                        isNew ? null : definition.instructions())
                        .asEditable())
                .field(UiField.text("welcomeMessage", "Welcome Message",
                        isNew ? null : agent.welcomeMessage())
                        .asEditable())
                .field(UiField.select("llmConfigName", "LLM Config",
                        isNew ? null : definition.modelSettings().model(), llmOptions)
                        .asEditable().asRequired())
                .field(UiField.number("maxIterations", "Max Iterations",
                        isNew ? 10 : definition.maxRounds())
                        .asEditable()
                        .hint("Maximum tool-call rounds per turn (defaults to 10, raise to 30+ for deep research)"))
                .field(UiField.number(MAX_TOOL_RESULT_CHARS, "Max tool result chars",
                        isNew ? null : definition.metadata().get(ToolResultPlugin.MAX_RESULT_CHARS))
                        .asEditable()
                        .hint("How many characters of one tool result the model is shown at a time (blank: "
                                + ToolResultPlugin.DEFAULT_PAGE_CHARS + "). A longer result ends with a line "
                                + "saying how to read on with fetch_tool_result; the chat keeps it whole. "
                                + "A tool can have a limit of its own in the Tools tab"))
                .field(UiField.textarea("memoryConfig", "Memory (JSON)",
                        isNew ? null : memoryJson())
                        .asEditable()
                        .hint("How the history is fitted into the model's context. \"strategy\": window "
                                + "(default — the newest turns that fit), compact (as window, what is left "
                                + "out is summarised first), full, none. Also \"maxConversationRatio\" "
                                + "(share of the context window, 0.8), \"maxMessages\" (window only), "
                                + "\"summaryPlacement\": system_prompt or user_message. "
                                + "Leave blank to keep the current setting"))
                // Skills: the switch, then which of them. Empty is "all of
                // them" — a skill added later is then in reach without
                // touching every agent.
                // Which skills the agent may load. The picker under the mode
                // is shown for SPECIFIC only — the mode change swaps it in place.
                .field(UiField.select(SKILLS_MODE, "Skills", skillsMode, List.of(
                                UiField.Option.of("NONE", "None"),
                                UiField.Option.of("ALL", "All skills"),
                                UiField.Option.of("SPECIFIC", "Specific skills")))
                        .asEditable()
                        .hint("None: neither the skill tool nor a word about skills in the prompt. "
                                + "All: every skill the installation, the user and the project have, "
                                + "one added later included. Specific: only the ones ticked below")
                        .onChange(UiTrigger.api("POST", skillsFieldUrl(), id())))
                .field(skillsField(skillsMode, skillNames))
                // The roster this agent delegates to — and the only thing
                // that gives it run_agent and list_agents, so the hint says so.
                // A checkbox per candidate: the roster is a set, its order says nothing.
                .field(UiField.multiselect("callableAgents", "Callable Agents",
                        roster, rosterOptions)
                        .asCheckboxes()
                        .asEditable()
                        .hint("The agents this one may hand work to. Naming any gives it run_agent, "
                                + "run_agents and list_agents; naming none leaves it without"))
                .field(UiField.bool(SubAgentPlugin.CALLABLE, "Callable by other agents",
                        isNew || SubAgentPlugin.callable(definition))
                        .asEditable()
                        .hint("Off for agents the host calls on its own, like a title generator "
                                + "or a summarizer: they are not offered in any roster and "
                                + "run_agent refuses them"))
                // Checkboxes whose ticked rows move up and down: the reviewers
                // run in the order they stand in.
                .field(UiField.multiselect("responseReviewers", "Response Reviewers",
                        reviewers, reviewerOptions)
                        .orderable()
                        .asEditable()
                        .hint("Agents that review and may rewrite the response, in order. "
                                + "Offered: the agents in the '" + AgentSetting.REVIEWER_GROUP
                                + "' group — file an agent there to make it a candidate"))
                .action(UiAction.primary("save", "Save").icon("save")
                        .onClick(isNew
                                ? trigger(on(AgentUiController.class).create(null, null), id())
                                : trigger(on(AgentUiController.class).update(agent.id().value(), null, null), id())))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .onClick(isNew
                                ? trigger(on(AgentUiController.class).list(null))
                                : trigger(on(AgentUiController.class).detail(agent.id().value(), null, null, null))))
                .link(UiLink.of("back", "/admin/agents", "← Back to Agents"));

        return UiStack.of(id() + "-page").child(header).child(form);
    }

    /**
     * The skills to choose from: the installation's, plus whatever the agent
     * already names — a name that came from a file or another host stays
     * selectable, so opening the form cannot silently drop it.
     */
    /**
     * The options of a picker over other agents: what the agent already
     * names, first and in its stored order (the orderable field shows the
     * ticked rows in that order), then every other agent that is
     * {@code eligible}, by name. An entry already named stays offered
     * whatever its eligibility now — one moved out of the reviewer group,
     * say — so saving the form untouched never drops it; one whose agent no
     * longer exists is dropped, as it would have been before. Deprecated
     * agents are not offered, as in the chat's picker, and neither is the
     * agent itself.
     */
    private List<UiField.Option> candidates(java.util.function.Predicate<ManagedAgent> eligible,
                                            List<String> alreadyNamed) {
        java.util.Set<String> existing = all.stream().map(other -> other.definition().name())
                .collect(java.util.stream.Collectors.toSet());
        var names = new java.util.LinkedHashSet<String>();
        alreadyNamed.stream().filter(existing::contains).filter(n -> !isSelf(n)).forEach(names::add);
        all.stream()
                .filter(other -> !isSelf(other.definition().name()))
                .filter(other -> other.status() != ManagedAgent.Status.DEPRECATED)
                .filter(eligible)
                .map(other -> other.definition().name())
                .sorted()
                .forEach(names::add);
        return names.stream().map(n -> UiField.Option.of(n, n)).toList();
    }

    private boolean isSelf(String name) {
        return agent != null && definition.name().equals(name);
    }

    /** The form field carrying the skills mode. */
    public static final String SKILLS_MODE = "skillsMode";

    /** The form field for the agent's page of a tool result; the tool form has a field of the stored name. */
    public static final String MAX_TOOL_RESULT_CHARS = "maxToolResultChars";

    /** The mode a form submitted, or {@link AgentSetting#ALL} for anything it did not name. */
    public static String skillsMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return AgentSetting.NONE.equals(mode) || AgentSetting.SPECIFIC.equals(mode) ? mode : AgentSetting.ALL;
    }

    /** Where the mode select posts the form to get the picker re-rendered. */
    private String skillsFieldUrl() {
        return "/admin/api/agents/skills-field" + (agent == null ? "" : "?id=" + agent.id().value());
    }

    /**
     * The skills picker — a checkbox per skill the catalog knows, plus the
     * names {@code ticked} in case one of them is not in the catalog any
     * more — hidden unless the mode is SPECIFIC, where it is the only thing
     * that decides. Hidden, it still submits, and the config drops the names.
     */
    public UiField skillsField(String mode, List<String> ticked) {
        List<String> picked = ticked == null ? List.of() : ticked;
        java.util.SortedSet<String> names = new java.util.TreeSet<>(picked);
        if (skills != null) {
            skills.findAll().stream().filter(Skill::enabled).map(Skill::name).forEach(names::add);
        }
        UiField field = UiField.multiselect("skills", "Skills to load", picked,
                        names.stream().map(name -> UiField.Option.of(name, name)).toList())
                .asCheckboxes()
                .asEditable()
                .hint("The skills this agent may load — none ticked is none");
        return AgentSetting.SPECIFIC.equals(mode) ? field : field.<UiField>hidden();
    }

    /**
     * The memory setting the agent carries, or the one an agent that
     * carries none gets — pre-filling the field with the real current state
     * makes the knobs discoverable instead of doc-only knowledge.
     */
    private String memoryJson() {
        Object memory = definition.metadata().get(MemoryPlugin.MEMORY);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(memory instanceof java.util.Map<?, ?> ? memory
                            : java.util.Map.of("strategy", "window"));
        } catch (Exception e) {
            return "";
        }
    }
}
