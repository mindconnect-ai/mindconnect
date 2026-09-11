package ai.mindconnect.agent.runtime.service.agents;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project keeps its own sub-agents beside its code, one Markdown file each.
 * What they may do is the caller's business: the file narrows the caller's
 * tools and can never widen them, which is what makes opening a strange
 * repository safe.
 */
class ProjectAgentsTest {

    @TempDir
    Path project;

    private Path agentsDir() throws Exception {
        return Files.createDirectories(project.resolve(ProjectAgents.DIR));
    }

    private void write(String fileName, String content) throws Exception {
        Files.writeString(agentsDir().resolve(fileName), content);
    }

    private static AgentTool tool(String name, boolean needsApproval) {
        return new AgentTool(AgentToolId.random(), name, name + " does things",
                Map.of(), true, false, needsApproval, null);
    }

    private static final List<AgentTool> CALLER_TOOLS = List.of(
            tool("file_read", false), tool("grep", false), tool("bash", true), tool("file_write", false));

    @Test
    void aFileBecomesAnAgent() throws Exception {
        write("verifier.md", """
                ---
                name: verifier
                description: Checks that a change really builds
                tools: bash, file_read
                model: claude-haiku-default
                ---
                You verify. Run the build, then say what you ran.
                """);

        var agent = ProjectAgents.find(project.toString(), "verifier").orElseThrow();

        assertThat(agent.name()).isEqualTo("verifier");
        assertThat(agent.description()).isEqualTo("Checks that a change really builds");
        assertThat(agent.model()).isEqualTo("claude-haiku-default");
        assertThat(agent.tools()).containsExactly("bash", "file_read");
        assertThat(agent.systemPrompt()).isEqualTo("You verify. Run the build, then say what you ran.");
        assertThat(ProjectAgents.find(project.toString(), "VERIFIER")).as("the name is not case-sensitive")
                .isPresent();
    }

    @Test
    void theFileNameNamesTheAgentWhenTheFrontMatterDoesNot() throws Exception {
        write("migration-writer.md", "Write migrations the way this schema wants them.");
        write("bare.md", """
                ---
                description: no name of its own
                ---
                Do the thing.
                """);

        assertThat(ProjectAgents.find(project.toString(), "migration-writer")).isPresent();
        var bare = ProjectAgents.find(project.toString(), "bare").orElseThrow();
        assertThat(bare.description()).isEqualTo("no name of its own");
        assertThat(bare.tools()).as("no tools field means the caller's own").isNull();
    }

    @Test
    void anEmptyToolsListKeepsNothing() throws Exception {
        write("flow.md", """
                ---
                tools: []
                ---
                Think, do not act.
                """);
        write("bare-brackets.md", """
                ---
                tools: [ ]
                disallowedTools: []
                ---
                Think, do not act.
                """);

        for (String name : List.of("flow", "bare-brackets")) {
            var agent = ProjectAgents.find(project.toString(), name).orElseThrow();
            assertThat(agent.tools()).as(name + " names a list, and the list is empty").isEmpty();
            assertThat(agent.toolsFrom(CALLER_TOOLS))
                    .as(name + ": an empty list is the narrowest set, not the widest")
                    .isEmpty();
        }
    }

    @Test
    void aToolsKeyWithNoValueAtAllSaysNothing() throws Exception {
        // YAML reads a key without a value as null, not as a list: the same
        // as leaving the key out.
        write("unset.md", """
                ---
                tools:
                description: nothing named
                ---
                Everything the caller has.
                """);

        var agent = ProjectAgents.find(project.toString(), "unset").orElseThrow();

        assertThat(agent.tools()).isNull();
        assertThat(agent.description()).isEqualTo("nothing named");
        assertThat(agent.toolsFrom(CALLER_TOOLS)).hasSize(CALLER_TOOLS.size());
    }

    @Test
    void theToolsAreTheCallersOwn_narrowedNeverWidened() throws Exception {
        write("reader.md", """
                ---
                tools: file_read, grep, database_drop
                ---
                Read only.
                """);
        var tools = ProjectAgents.find(project.toString(), "reader").orElseThrow()
                .toolsFrom(CALLER_TOOLS);

        assertThat(tools).extracting(AgentTool::name)
                .as("a name the caller does not have grants nothing")
                .containsExactly("file_read", "grep");
        assertThat(tools).extracting(AgentTool::id)
                .as("each binding is the agent's own, not the caller's")
                .doesNotContainAnyElementsOf(CALLER_TOOLS.stream().map(AgentTool::id).toList());
    }

    @Test
    void anApprovalTheCallerNeedsIsKept() throws Exception {
        write("builder.md", """
                ---
                tools: bash
                ---
                Build it.
                """);

        var tools = ProjectAgents.find(project.toString(), "builder").orElseThrow()
                .toolsFrom(CALLER_TOOLS);

        assertThat(tools).singleElement()
                .extracting(AgentTool::name, AgentTool::needsApproval)
                .as("the file cannot wave bash through")
                .containsExactly("bash", true);
    }

    @Test
    void namingNoToolsKeepsTheCallersSet_andDisallowedTakesAway() throws Exception {
        write("all.md", "Everything the caller has.");
        write("no-writes.md", """
                ---
                disallowedTools: file_write, bash
                ---
                Look, do not touch.
                """);

        assertThat(ProjectAgents.find(project.toString(), "all").orElseThrow()
                .toolsFrom(CALLER_TOOLS))
                .extracting(AgentTool::name)
                .containsExactly("file_read", "grep", "bash", "file_write");

        assertThat(ProjectAgents.find(project.toString(), "no-writes").orElseThrow()
                .toolsFrom(CALLER_TOOLS))
                .extracting(AgentTool::name)
                .containsExactly("file_read", "grep");
    }

    @Test
    void listsWhatIsThere_andIsQuietWhenNothingIs() throws Exception {
        assertThat(ProjectAgents.list(project.toString())).as("no directory").isEmpty();
        assertThat(ProjectAgents.list(null)).as("no working directory").isEmpty();
        assertThat(ProjectAgents.find(null, "verifier")).isEmpty();

        agentsDir();
        assertThat(ProjectAgents.list(project.toString())).as("empty directory").isEmpty();

        write("b-second.md", "Second.");
        write("a-first.md", "First.");
        Files.writeString(agentsDir().resolve("notes.txt"), "not an agent");
        Files.writeString(agentsDir().resolve("empty.md"), "---\nname: nothing\n---\n\n   ");

        assertThat(ProjectAgents.list(project.toString())).extracting(ProjectAgents.ProjectAgent::name)
                .as("markdown only, in file order, and a file without a prompt is not an agent")
                .containsExactly("a-first", "b-second");
    }

    @Test
    void frontMatterThatIsNotFrontMatterIsJustPrompt() {
        var noFence = ProjectAgents.parse("plain", "Just a prompt.\n\n---\n\nWith a rule in it.");
        assertThat(noFence).isNotNull();
        assertThat(noFence.name()).isEqualTo("plain");
        assertThat(noFence.systemPrompt()).startsWith("Just a prompt.").contains("With a rule in it.");

        var unclosed = ProjectAgents.parse("odd", "---\nname: x\nstill going");
        assertThat(unclosed).isNotNull();
        assertThat(unclosed.name()).as("an unclosed fence is not front matter").isEqualTo("odd");

        assertThat(ProjectAgents.parse("blank", "   \n\n")).as("nothing to run on").isNull();
    }

    @Test
    void quotesAndBracketsInTheFrontMatterAreTolerated() {
        var agent = ProjectAgents.parse("x", """
                ---
                name: "quoted-name"
                description: 'single quoted'
                tools: [file_read, "grep"]
                ---
                Body.
                """);

        assertThat(agent.name()).isEqualTo("quoted-name");
        assertThat(agent.description()).isEqualTo("single quoted");
        assertThat(agent.tools()).containsExactly("file_read", "grep");
    }

    @Test
    void aBlockListMeansTheSameAsACommaList() throws Exception {
        // The shape anyone writing YAML reaches for. Read as an empty value
        // it would leave the agent with every tool its caller has, which is
        // the opposite of what a file naming two tools asks for.
        write("reader.md", """
                ---
                name: reader
                tools:
                  - file_read
                  - grep
                disallowedTools:
                  - bash
                ---
                Read only.
                """);

        var agent = ProjectAgents.find(project.toString(), "reader").orElseThrow();

        assertThat(agent.tools()).containsExactly("file_read", "grep");
        assertThat(agent.disallowedTools()).containsExactly("bash");
        assertThat(agent.toolsFrom(CALLER_TOOLS))
                .extracting(AgentTool::name).containsExactly("file_read", "grep");
    }

    @Test
    void aBlankLineInsideABlockListDoesNotEndIt() throws Exception {
        // Ending the list at the blank line would leave tools without a
        // value, and with it every tool the caller has.
        write("spaced.md", """
                ---
                tools:

                  - file_read

                  - grep
                model: claude-haiku-default
                ---
                Read only.
                """);

        var agent = ProjectAgents.find(project.toString(), "spaced").orElseThrow();

        assertThat(agent.tools()).containsExactly("file_read", "grep");
        assertThat(agent.model()).as("the next key is read as a key").isEqualTo("claude-haiku-default");
        assertThat(agent.toolsFrom(CALLER_TOOLS))
                .extracting(AgentTool::name).containsExactly("file_read", "grep");
    }

    @Test
    void aToolNamedByHandIsOfferedEvenWhenTheCallerDefersIt() throws Exception {
        // A deferred tool waits for a tool search, and a project agent has
        // none — so one it names by hand would otherwise be unreachable.
        AgentTool deferred = new AgentTool(AgentToolId.random(), "mcp__db__query",
                "queries", Map.of(), true, true, false, null);
        List<AgentTool> caller = List.of(deferred, tool("file_read", false));

        write("named.md", """
                ---
                tools: mcp__db__query
                ---
                Query it.
                """);
        write("inherits.md", "Everything the caller has.");

        assertThat(ProjectAgents.find(project.toString(), "named").orElseThrow()
                .toolsFrom(caller))
                .singleElement()
                .extracting(AgentTool::name, AgentTool::deferred)
                .containsExactly("mcp__db__query", false);

        assertThat(ProjectAgents.find(project.toString(), "inherits").orElseThrow()
                .toolsFrom(caller))
                .as("one merely inherited keeps its place in the search space")
                .filteredOn(t -> t.name().equals("mcp__db__query"))
                .singleElement().extracting(AgentTool::deferred).isEqualTo(true);
    }

    @Test
    void aPromptThatOpensWithARuleKeepsIt() {
        var agent = ProjectAgents.parse("reviewer", """
                ---
                You are a reviewer.
                ---
                Be terse.
                """);

        assertThat(agent).isNotNull();
        assertThat(agent.name()).as("no field between the fences, so no front matter").isEqualTo("reviewer");
        assertThat(agent.systemPrompt())
                .as("the opening line is part of the prompt, not swallowed")
                .contains("You are a reviewer.").contains("Be terse.");
    }
}
