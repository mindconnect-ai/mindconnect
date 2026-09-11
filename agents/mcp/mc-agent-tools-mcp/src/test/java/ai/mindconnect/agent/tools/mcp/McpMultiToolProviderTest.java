package ai.mindconnect.agent.tools.mcp;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerInfo;
import ai.mindconnect.mcp.gateway.McpTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpMultiToolProviderTest {

    private static final ToolCallScope SCOPE =
            new ToolCallScope(UserId.of("alice"), SessionId.random(), AgentId.random());

    @Test
    void tool_names_are_the_servers_prefix_plus_the_sub_tool() {
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("gmail", "gmail", "search_emails", "read_email"));

        assertThat(provider.toolNames()).containsExactly("gmail_search_emails", "gmail_read_email");
        assertThat(provider.group()).isEqualTo("mcp");
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void tool_names_keep_the_order_the_servers_reported_them_in() {
        // The SPI asks for a stable order — the catalog and the agent's tool
        // picker render this list. Map.copyOf would not give it.
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("files", "f", "delta", "alpha", "charlie", "bravo"));

        assertThat(provider.toolNames()).containsExactly("f_delta", "f_alpha", "f_charlie", "f_bravo");
    }

    @Test
    void two_servers_live_side_by_side_under_their_own_prefixes() {
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("gmail", "gmail", "search_emails")
                .server("github-work", "gh_work", "create_issue"));

        assertThat(provider.toolNames()).containsExactly("gmail_search_emails", "gh_work_create_issue");
    }

    @Test
    void a_name_claimed_twice_stays_with_the_first_server() {
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("first", "same", "thing")
                .server("second", "same", "thing"));

        assertThat(provider.toolNames()).containsExactly("same_thing");
        Tool tool = provider.create("same_thing", AgentTool.of("same_thing"), SCOPE).orElseThrow();
        assertThat(tool.execute(Map.of())).isEqualTo("called first/thing");
    }

    @Test
    void the_created_tool_carries_the_servers_own_description_and_schema() {
        Tool tool = boundTo(new FakeGateway().server("gmail", "gmail", "search_emails"))
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)
                .orElseThrow();

        assertThat(tool.name()).isEqualTo("gmail_search_emails");
        assertThat(tool.description()).isEqualTo("description of search_emails");
        assertThat(tool.parametersSchema()).containsKey("type");
    }

    @Test
    void the_call_reaches_the_gateway_with_the_scopes_user_and_session() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        boundTo(gateway).create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)
                .orElseThrow()
                .execute(Map.of("query", "from:someone"));

        assertThat(gateway.calls).singleElement().satisfies(call -> {
            assertThat(call.caller()).isEqualTo(new McpCaller(UserId.of("alice"), SCOPE.sessionId()));
            assertThat(call.serverId()).isEqualTo(McpServerId.of("gmail"));
            assertThat(call.toolName()).isEqualTo("search_emails");
            assertThat(call.arguments()).containsEntry("query", "from:someone");
        });
    }

    @Test
    void the_catalog_can_read_description_and_schema_without_a_session() {
        // ToolCatalogUiController resolves detached — no user, no session —
        // purely to render the row. That must yield a usable tool object.
        Tool tool = boundTo(new FakeGateway().server("gmail", "gmail", "search_emails"))
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), ToolCallScope.detached(null))
                .orElseThrow();

        assertThat(tool.description()).isEqualTo("description of search_emails");
        assertThat(tool.parametersSchema()).containsKey("type");
    }

    @Test
    void calling_such_a_sessionless_tool_says_why_instead_of_throwing() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");

        String answer = boundTo(gateway)
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), ToolCallScope.detached(null))
                .orElseThrow()
                .execute(Map.of());

        // "Error:" is how the tool-call worker and the workflow step tell a
        // failure — a workflow started from the admin has no session either.
        assertThat(answer).startsWith("Error:").contains("agent session");
        assertThat(gateway.calls).isEmpty();
    }

    @Test
    void a_server_registered_while_running_shows_up_without_a_restart() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        McpMultiToolProvider provider = boundTo(gateway);
        assertThat(provider.toolNames()).containsExactly("gmail_search_emails");

        gateway.server("files", "f", "read_file");   // as the admin UI would

        assertThat(provider.toolNames()).containsExactly("gmail_search_emails", "f_read_file");
        assertThat(provider.create("f_read_file", AgentTool.of("f_read_file"), SCOPE)).isPresent();
    }

    @Test
    void an_unchanged_catalog_is_not_rebuilt_on_every_lookup() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        McpMultiToolProvider provider = boundTo(gateway);
        provider.toolNames();
        int afterFirstLookup = gateway.serverListings;

        provider.toolNames();
        provider.toolNames();
        provider.toolNames();

        assertThat(gateway.serverListings).isEqualTo(afterFirstLookup);
    }

    @Test
    void the_first_lookup_after_a_start_already_finds_the_tools() {
        // Nothing lists the catalog before an agent's first turn resolves its
        // tools. That lookup has to find them on its own.
        McpMultiToolProvider provider = boundTo(new FakeGateway().server("gmail", "gmail", "search_emails"));

        assertThat(provider.create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)).isPresent();
    }

    @Test
    void every_tool_names_the_server_it_came_from_as_its_subgroup() {
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("gmail", "gmail", "search_emails")
                .server("github-work", "gh_work", "create_issue"));

        assertThat(provider.subgroup("gmail_search_emails")).isEqualTo("gmail");
        assertThat(provider.subgroup("gh_work_create_issue")).isEqualTo("github-work");
        assertThat(provider.subgroup("nothing_here")).isNull();
    }

    @Test
    void an_unknown_name_is_left_to_the_next_provider() {
        assertThat(boundTo(new FakeGateway().server("gmail", "gmail", "search_emails"))
                .create("something_else", AgentTool.of("something_else"), SCOPE))
                .isEmpty();
    }

    @Test
    void without_a_gateway_the_provider_stays_out_of_the_way() {
        McpMultiToolProvider provider = new McpMultiToolProvider();
        provider.bind(MapToolEnvironment.builder().build());

        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.toolNames()).isEmpty();
        assertThat(provider.create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)).isEmpty();
    }

    @Test
    void a_server_that_offers_no_tools_does_not_take_the_others_with_it() {
        McpMultiToolProvider provider = boundTo(new FakeGateway()
                .server("unreachable", "broken")
                .server("gmail", "gmail", "search_emails"));

        assertThat(provider.toolNames()).containsExactly("gmail_search_emails");
    }

    @Test
    void a_failing_call_becomes_a_message_for_the_model_not_an_exception() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        gateway.failWith = new McpGatewayException("container did not start");

        String answer = boundTo(gateway)
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)
                .orElseThrow()
                .execute(Map.of());

        assertThat(answer).startsWith("Error:").contains("container did not start");
    }

    @Test
    void releasing_a_session_releases_it_at_the_gateway() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");

        boundTo(gateway).releaseSession(SCOPE.sessionId());

        assertThat(gateway.released).containsExactly(SCOPE.sessionId());
    }

    @Test
    void an_empty_answer_is_named_rather_than_handed_over_blank() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        gateway.answer = new McpResult(false, List.of(""));

        assertThat(boundTo(gateway)
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)
                .orElseThrow()
                .execute(Map.of()))
                .startsWith("No results.");
    }

    @Test
    void an_error_result_is_relayed_as_an_error_message() {
        FakeGateway gateway = new FakeGateway().server("gmail", "gmail", "search_emails");
        gateway.answer = new McpResult(true, List.of("invalid query"));

        assertThat(boundTo(gateway)
                .create("gmail_search_emails", AgentTool.of("gmail_search_emails"), SCOPE)
                .orElseThrow()
                .execute(Map.of()))
                .isEqualTo("Error: the MCP server reported: invalid query");
    }

    private static McpMultiToolProvider boundTo(McpGateway gateway) {
        McpMultiToolProvider provider = new McpMultiToolProvider();
        provider.bind(MapToolEnvironment.builder().service(McpGateway.class, gateway).build());
        return provider;
    }

    /** Records what it was asked, answers what it was told to. */
    private static final class FakeGateway implements McpGateway {

        record Call(McpCaller caller, McpServerId serverId, String toolName, Map<String, Object> arguments) {
        }

        private final List<McpServerInfo> servers = new ArrayList<>();
        private final Map<String, List<McpTool>> tools = new LinkedHashMap<>();
        private long version;
        final List<Call> calls = new ArrayList<>();
        int serverListings;
        McpResult answer;
        RuntimeException failWith;

        FakeGateway server(String id, String prefix, String... toolNames) {
            version++;
            servers.add(new McpServerInfo(McpServerId.of(id), id, null, prefix));
            List<McpTool> list = new ArrayList<>();
            for (String name : toolNames) {
                list.add(new McpTool(name, "description of " + name, Map.of("type", "object")));
            }
            tools.put(id, list);
            return this;
        }

        @Override public long catalogVersion() {
            return version;
        }

        @Override public List<McpServerInfo> servers() {
            serverListings++;
            return servers;
        }

        @Override public List<McpTool> tools(McpServerId serverId) {
            return tools.getOrDefault(serverId.value(), List.of());
        }

        @Override
        public McpResult call(McpCaller caller, McpServerId serverId, String toolName, Map<String, Object> arguments) {
            calls.add(new Call(caller, serverId, toolName, arguments));
            if (failWith != null) throw failWith;
            return answer != null ? answer : new McpResult(false, List.of("called " + serverId.value() + "/" + toolName));
        }

        final List<SessionId> released = new ArrayList<>();

        @Override public void release(McpCaller caller) {
            released.add(caller.sessionId());
        }
    }
}
