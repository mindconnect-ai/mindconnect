package ai.mindconnect.cli;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.cli.agentclient.AgentClient;
import ai.mindconnect.cli.agentclient.LocalClientFactory;
import ai.mindconnect.cli.agentclient.RemoteClientFactory;
import ai.mindconnect.common.Namespace;
import org.springframework.beans.factory.annotation.Value;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.ParticipantType;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;

@Component
public class CliRunner implements CommandLineRunner {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final com.fasterxml.jackson.databind.ObjectMapper PRETTY_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final LocalClientFactory localFactory;
    private final RemoteClientFactory remoteFactory;
    private final InitialDataLoader initialDataLoader;
    private final CliStateStore stateStore;
    private final String userId;

    public CliRunner(LocalClientFactory localFactory,
                     RemoteClientFactory remoteFactory,
                     InitialDataLoader initialDataLoader,
                     CliStateStore stateStore,
                     @Value("${mindconnect.user.id:${user.name}}") String userId) {
        this.localFactory = localFactory;
        this.remoteFactory = remoteFactory;
        this.initialDataLoader = initialDataLoader;
        this.stateStore = stateStore;
        this.userId = userId;
    }

    @Override
    public void run(String... args) {
        Scanner scanner = new Scanner(System.in);
        Namespace namespace = new Namespace("local");

        printBanner();

        AgentClient client;

        if (remoteFactory.isConfigured()) {
            client = remoteFactory.createAgentClient();
            System.out.println("[Remote mode → " + remoteFactory.getRemoteUrl() + "]\n");
        } else {
            client = localFactory.createAgentClient();
            System.out.println("[Local mode]\n");
        }

        initialDataLoader.load((type, name, diff) -> {
            System.out.println("\n\033[33m⚠ Initial data update: " + type + " '" + name + "' differs from stored version:\033[0m");
            System.out.print(diff);
            System.out.print("Overwrite stored version? [y/N] ");
            String answer = scanner.nextLine().trim();
            return answer.equalsIgnoreCase("y");
        });

        // Resume "where the user was" from the persisted CLI state. Falls back gracefully
        // if the stored agent or session no longer exists (e.g. deleted between runs).
        Resume resume = resolveResume(stateStore.load(), namespace, userId, client);

        while (true) {
            AgentDefinition selectedAgent = resume.agent != null
                    ? resume.agent
                    : selectAgent(scanner, namespace, client);
            if (selectedAgent == null) break;

            AgentSession session;
            if (resume.session != null) {
                session = resume.session;
                System.out.println("[Resumed session " + sessionLabel(session) + "]");
            } else {
                stateStore.save(CliState.sessionList(selectedAgent.id()));
                session = pickOrStartSession(scanner, selectedAgent, namespace, userId, client);
                if (session == null) {
                    resume = Resume.none();
                    stateStore.save(CliState.agentList());
                    continue;
                }
            }
            // Resume hint consumed — subsequent loop iterations behave normally.
            resume = Resume.none();
            stateStore.save(CliState.activeSession(selectedAgent.id(), session.id()));

            printSessionHeader(selectedAgent, session);
            printHistory(session, client);

            while (true) {
                System.out.print("\nYou: ");
                String input = scanner.nextLine().trim();

                if (input.equals("/quit") || input.equals("/exit")) {
                    System.out.println("Goodbye!");
                    return;
                }

                if (input.equals("/back")) {
                    System.out.println("[Returned to agent selection.]");
                    stateStore.save(CliState.agentList());
                    break;
                }

                if (input.equals("/new")) {
                    session = client.startSession(selectedAgent.id(), namespace, userId);
                    stateStore.save(CliState.activeSession(selectedAgent.id(), session.id()));
                    printSessionHeader(selectedAgent, session);
                    printHistory(session, client);
                    continue;
                }

                if (input.equals("/sessions")) {
                    AgentSession picked = pickSession(scanner, selectedAgent, namespace, userId, client);
                    if (picked != null) {
                        session = picked;
                        stateStore.save(CliState.activeSession(selectedAgent.id(), session.id()));
                        printSessionHeader(selectedAgent, session);
                        printHistory(session, client);
                    }
                    continue;
                }

                if (input.equals("/help")) {
                    printHelp();
                    continue;
                }

                if (input.equals("/tools")) {
                    printTools(selectedAgent, client);
                    continue;
                }

                if (input.equals("/tool-calls") || input.startsWith("/tool-calls ")) {
                    String arg = input.length() > "/tool-calls".length()
                            ? input.substring("/tool-calls".length()).trim() : "";
                    printToolCallHistory(session, client, arg);
                    continue;
                }

                if (input.equals("/messages") || input.startsWith("/messages ")) {
                    String arg = input.length() > "/messages".length()
                            ? input.substring("/messages".length()).trim() : "";
                    printMessageList(session, client, arg);
                    continue;
                }

                if (input.equals("/history") || input.startsWith("/history ")) {
                    String arg = input.length() > "/history".length()
                            ? input.substring("/history".length()).trim() : "";
                    printHistoryWithArg(session, client, arg);
                    continue;
                }

                if (input.equals("/memory")) {
                    printMemory(session, selectedAgent, client);
                    continue;
                }

                if (input.equals("/compress")) {
                    compressMemory(session, client);
                    continue;
                }

                if (input.startsWith("/message ")) {
                    String arg = input.substring("/message ".length()).trim();
                    try {
                        int seq = Integer.parseInt(arg);
                        printMessage(session, seq, client);
                    } catch (NumberFormatException e) {
                        System.out.println("[Usage: /message <sequenceNum>]");
                    }
                    continue;
                }

                if (input.startsWith("/delete-message ")) {
                    String arg = input.substring("/delete-message ".length()).trim();
                    int[] range = parseDeleteMessageRange(arg, session, client);
                    if (range == null) {
                        System.out.println("[Usage: /delete-message <seq>" +
                                "  |  /delete-message <from>-<to>" +
                                "  |  /delete-message <from>-last" +
                                "  |  /delete-message last-<n>]");
                        continue;
                    }
                    int fromSeq = range[0];
                    int toSeq = range[1];
                    // Tolerate reversed ranges ("10-1" = same as "1-10").
                    if (toSeq < fromSeq) {
                        int swap = fromSeq; fromSeq = toSeq; toSeq = swap;
                    }
                    int deleted = client.deleteMessages(session.id(), fromSeq, toSeq);
                    if (deleted == 0) {
                        System.out.println("[No messages found in range " + fromSeq + "-" + toSeq + ".]");
                    } else {
                        System.out.println("✓ Deleted " + deleted + " message(s) (seq " + fromSeq + "-" + toSeq + ").");
                    }
                    continue;
                }

                if (input.isEmpty()) continue;

                streamAgentResponse(session, input, client);
            }
        }
    }

    private AgentSession pickOrStartSession(Scanner scanner, AgentDefinition agent,
                                             Namespace namespace, String userId, AgentClient client) {
        List<AgentSession> sessions = client.listSessions(agent.id(), namespace, userId);
        if (sessions.isEmpty()) {
            return client.startSession(agent.id(), namespace, userId);
        }

        while (true) {
            List<AgentSession> current = client.listSessions(agent.id(), namespace, userId);
            System.out.println("\nSessions for " + agent.name() + ":");
            System.out.println("  0) [New session]");
            for (int i = 0; i < current.size(); i++) {
                System.out.println("  " + (i + 1) + ") " + sessionLabel(current.get(i)));
            }
            System.out.print("Select session (number), /delete <n>, or /back: ");
            String input = scanner.nextLine().trim();

            if (input.equals("/back") || input.equals("/quit")) return null;

            if (input.startsWith("/delete ")) {
                try {
                    int idx = Integer.parseInt(input.substring("/delete ".length()).trim());
                    if (idx >= 1 && idx <= current.size()) {
                        AgentSession toDelete = current.get(idx - 1);
                        client.deleteSession(toDelete.id());
                        System.out.println("✓ Session " + idx + " deleted.");
                    } else {
                        System.out.println("[Invalid session number.]");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("[Usage: /delete <n>]");
                }
                continue;  // re-display updated list
            }

            try {
                int idx = Integer.parseInt(input);
                if (idx == 0) return client.startSession(agent.id(), namespace, userId);
                if (idx >= 1 && idx <= current.size()) {
                    return current.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {}

            System.out.println("[Invalid selection, starting new session.]");
            return client.startSession(agent.id(), namespace, userId);
        }
    }

    private AgentSession pickSession(Scanner scanner, AgentDefinition agent,
                                      Namespace namespace, String userId, AgentClient client) {
        List<AgentSession> sessions = client.listSessions(agent.id(), namespace, userId);
        if (sessions.isEmpty()) {
            System.out.println("[No sessions found.]");
            return null;
        }

        System.out.println("\nSessions for " + agent.name() + ":");
        for (int i = 0; i < sessions.size(); i++) {
            System.out.println("  " + (i + 1) + ") " + sessionLabel(sessions.get(i)));
        }
        System.out.print("Select session (number) or enter to cancel: ");
        String input = scanner.nextLine().trim();
        if (input.isEmpty()) return null;

        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < sessions.size()) {
                return sessions.get(idx);
            }
        } catch (NumberFormatException ignored) {}

        System.out.println("[Invalid selection.]");
        return null;
    }

    private String sessionLabel(AgentSession session) {
        String date = DATE_FMT.format(session.startedAt());
        String title = session.title() != null ? session.title() : "(untitled)";
        return title + "  [" + date + "]  " + session.id().toString().substring(0, 8) + "…";
    }

    private void printSessionHeader(AgentDefinition agent, AgentSession session) {
        System.out.println("\n[Session: " + sessionLabel(session) + "]");
        System.out.println("Type /back, /sessions, /tools, /tool-calls, /messages, /history, /memory, /compress, /quit or /help.");
    }

    private void printHistory(AgentSession session, AgentClient client) {
        renderHistory(session, client, client.loadHistory(session.id()), null);
    }

    /**
     * Slash-command variant. Accepts:
     * <ul>
     *   <li>empty / no arg → whole conversation (same as the auto-print on session open)</li>
     *   <li>{@code "all"}  → same as default</li>
     *   <li>integer N      → only the last N CHAT pairs (each pair = user + agent)</li>
     * </ul>
     */
    private void printHistoryWithArg(AgentSession session, AgentClient client, String arg) {
        List<Message> all;
        try { all = client.loadHistory(session.id()); }
        catch (Exception e) {
            System.out.println("[Failed to load history: " + e.getMessage() + "]");
            return;
        }
        Integer pairLimit = null;
        if (arg != null && !arg.isEmpty() && !arg.equalsIgnoreCase("all")) {
            try {
                int n = Integer.parseInt(arg);
                if (n <= 0) {
                    System.out.println("[Argument must be a positive integer]");
                    return;
                }
                pairLimit = n;
            } catch (NumberFormatException e) {
                System.out.println("[Usage: /history  |  /history all  |  /history <n>]");
                return;
            }
        }
        renderHistory(session, client, all, pairLimit);
    }

    /**
     * Renders the CHAT-only view of a conversation. Tool-call clusters between an
     * agent CHAT and the next message are summarised as a one-line hint.
     * <p>
     * If {@code pairLimit} is non-null, only the last {@code pairLimit} CHAT pairs
     * (user + agent) are shown. The full message list is still passed in so that
     * tool-call counts attached to a kept agent message are computed correctly.
     */
    private void renderHistory(AgentSession session, AgentClient client,
                                List<Message> history, Integer pairLimit) {
        if (history.isEmpty()) {
            String welcome = client.findAgent(session.namespace(), session.agentDefinitionId())
                    .map(AgentDefinition::welcomeMessage).orElse(null);
            if (welcome != null && !welcome.isBlank()) {
                System.out.println("\nAgent:\n" + welcome);
            } else {
                System.out.println("\n[No messages in this session yet.]");
            }
            return;
        }

        // If a pair limit is set, find the sequence number from which onwards we render.
        int minSeqToShow = Integer.MIN_VALUE;
        if (pairLimit != null) {
            // Walk backwards over CHAT messages, count user→agent pairs.
            int pairsCounted = 0;
            for (int k = history.size() - 1; k >= 0; k--) {
                Message m = history.get(k);
                if (m.type() != ai.mindconnect.message.domain.MessageType.CHAT) continue;
                if (m.senderType() == ParticipantType.USER) {
                    pairsCounted++;
                    if (pairsCounted >= pairLimit) {
                        minSeqToShow = m.sequenceNum();
                        break;
                    }
                }
            }
            if (pairsCounted == 0) {
                System.out.println("\n[No CHAT messages to show.]");
                return;
            }
        }

        String headerSuffix = pairLimit != null
                ? " (last " + pairLimit + " pair" + (pairLimit > 1 ? "s" : "") + ")"
                : "";
        System.out.println("\n── History" + headerSuffix + " ─────────────────────────────");
        int i = 0;
        while (i < history.size()) {
            Message m = history.get(i);
            if (m.type() == ai.mindconnect.message.domain.MessageType.CHAT
                    && m.sequenceNum() >= minSeqToShow) {
                String speaker = m.senderType() == ParticipantType.USER ? "You" : "Agent";
                // Count any tool calls that follow this agent message before the next CHAT
                int toolCallCount = 0;
                if (m.senderType() == ParticipantType.AGENT) {
                    int j = i + 1;
                    while (j < history.size()
                            && history.get(j).type() != ai.mindconnect.message.domain.MessageType.CHAT) {
                        if (history.get(j).type() == ai.mindconnect.message.domain.MessageType.TOOL_CALL) {
                            toolCallCount++;
                        }
                        j++;
                    }
                }
                System.out.print("\n" + speaker + ":\n" + m.content());
                if (toolCallCount > 0) {
                    System.out.print("\n\033[90m(" + toolCallCount
                            + " tool call" + (toolCallCount > 1 ? "s" : "") + " executed — use /tool-calls for details)\033[0m");
                }
                System.out.println();
            }
            // Skip TOOL_CALL and TOOL_RESULT — shown as hint above
            i++;
        }
        System.out.println("── End of history ───────────────────────");
    }

    private AgentDefinition selectAgent(Scanner scanner, Namespace namespace, AgentClient client) {
        List<AgentDefinition> agents = client.listAgents(namespace);
        System.out.println("\nAvailable agents:");
        for (int i = 0; i < agents.size(); i++) {
            System.out.println("  " + (i + 1) + ") " + agents.get(i).name()
                    + " — " + agents.get(i).description());
        }
        System.out.print("\nSelect agent (number) or /quit: ");
        String input = scanner.nextLine().trim();

        if (input.equals("/quit") || input.equals("/exit")) {
            System.out.println("Goodbye!");
            return null;
        }

        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < agents.size()) return agents.get(idx);
        } catch (NumberFormatException ignored) {}

        System.out.println("[Invalid selection, please try again.]");
        return selectAgent(scanner, namespace, client);
    }

    private void streamAgentResponse(AgentSession session, String userMessage, AgentClient client) {
        System.out.println("\nAgent: \033[90m(press q + enter to cancel)\033[0m");
        boolean[] inStatus = {false};       // true while a status line is being shown
        boolean[] textActive = {false};     // true while we are currently printing tokens on a line
        int[] toolCallCount = {0};
        // Tracks how many lines of plain agent text we have already emitted, so we
        // can erase them again if a reviewer revises the answer afterwards.
        int[] streamedLines = {0};
        StringBuilder[] streamed = {new StringBuilder()};
        // Polled cancel watcher: while the stream runs, watch stdin for 'q\n'.
        // Lives only for the duration of this turn; stops itself when streamDone is set.
        java.util.concurrent.atomic.AtomicBoolean streamDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean cancelSent = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread cancelWatcher = startCancelWatcher(session.id(), client, streamDone, cancelSent);
        try {
            client.chat(session.id(), userMessage, event -> {
                // After the user has pressed q, drop any further events. In the
                // local-client path the LLM stream keeps producing tokens
                // server-side until the loop checks the cancel flag — without
                // this filter those tokens would still render. In the remote
                // path the SSE call is hard-aborted in cancelChat(), so this is
                // mostly defensive (covers a final in-flight frame).
                if (cancelSent.get()) return;
                switch (event) {
                    case StreamEvent.Token t -> {
                        if (inStatus[0]) {
                            // clear the status line and start normal text output
                            System.out.print("\r\033[K");
                            inStatus[0] = false;
                        }
                        System.out.print(t.text());
                        System.out.flush();
                        streamed[0].append(t.text());
                        textActive[0] = !t.text().endsWith("\n");
                        // Track newlines so we can later move the cursor up to clear them.
                        for (int i = 0; i < t.text().length(); i++) {
                            if (t.text().charAt(i) == '\n') streamedLines[0]++;
                        }
                    }
                    case StreamEvent.AskingLlm a -> {
                        endTextLine(textActive, streamedLines);
                        System.out.print("\r\033[K  ⏳ asking llm…");
                        System.out.flush();
                        inStatus[0] = true;
                    }
                    case StreamEvent.ToolCallStarted s -> {
                        endTextLine(textActive, streamedLines);
                        String label = truncate(formatArgs(s.arguments()), 60);
                        String status = "  ⚙ " + s.toolName() + (label.isEmpty() ? "" : "(" + label + ")") + "…";
                        System.out.print("\r\033[K" + status);
                        System.out.flush();
                        inStatus[0] = true;
                    }
                    case StreamEvent.ToolCallResult r -> {
                        endTextLine(textActive, streamedLines);
                        toolCallCount[0]++;
                        System.out.print("\r\033[K  ✓ " + r.toolName() + " (" + r.durationMs() + "ms)");
                        System.out.flush();
                        inStatus[0] = true;
                    }
                    case StreamEvent.Reviewing rv -> {
                        endTextLine(textActive, streamedLines);
                        System.out.print("\r\033[K  \033[90m⏳ reviewing (" + rv.reviewerName() + ")…\033[0m");
                        System.out.flush();
                        inStatus[0] = true;
                    }
                    case StreamEvent.ReviewerDecision rd -> {
                        // Replace the "reviewing…" status line with a permanent
                        // marker so the user always sees that the reviewer ran.
                        if (inStatus[0]) {
                            System.out.print("\r\033[K");
                            inStatus[0] = false;
                        }
                        String marker = switch (rd.verdict()) {
                            case PASSED   -> "✓";
                            case MODIFIED -> "✎";
                            case BLOCKED  -> "⛔";
                        };
                        String label = switch (rd.verdict()) {
                            case PASSED   -> "passed";
                            case MODIFIED -> "modified";
                            case BLOCKED  -> "blocked";
                        };
                        System.out.println("  \033[90m" + marker + " "
                                + rd.reviewerName() + " (" + label + ")\033[0m");
                        // The marker is one line, count it for any follow-up rewriter
                        // logic (for PASSED nothing else changes; for MODIFIED/BLOCKED
                        // ResponseRevised will erase streamed lines next).
                        streamedLines[0]++;
                    }
                    case StreamEvent.ResponseRevised rev -> {
                        // Erase the status line and everything we have streamed so far,
                        // including the ReviewerDecision marker line printed just before.
                        if (inStatus[0]) {
                            System.out.print("\r\033[K");
                            inStatus[0] = false;
                        }
                        for (int i = 0; i < streamedLines[0]; i++) {
                            System.out.print("\033[1A\033[2K");
                        }
                        System.out.print("\r\033[K");
                        // Reprint a slim header (marker is already shown by the ReviewerDecision
                        // handler — but that line was just erased, so we emit a fresh one).
                        String header = rev.blocked() ? "⛔" : "✎";
                        System.out.println("\033[90m" + header + " " + rev.reason() + "\033[0m");
                        System.out.print(rev.finalText());
                        System.out.flush();
                        streamed[0].setLength(0);
                        streamed[0].append(rev.finalText());
                        streamedLines[0] = 1;  // for the header line we just printed
                        for (int i = 0; i < rev.finalText().length(); i++) {
                            if (rev.finalText().charAt(i) == '\n') streamedLines[0]++;
                        }
                    }
                    case StreamEvent.Done d -> {
                        if (inStatus[0]) {
                            System.out.print("\r\033[K");
                            inStatus[0] = false;
                        }
                        endTextLine(textActive, streamedLines);
                    }
                    case StreamEvent.ToolCallFailed f -> {
                        endTextLine(textActive, streamedLines);
                        toolCallCount[0]++;
                        System.out.print("\r\033[K  ✗ " + f.toolName()
                                + " (" + f.durationMs() + "ms): " + f.error());
                        System.out.flush();
                        inStatus[0] = true;
                    }
                    case StreamEvent.SubAgentStarted s -> {
                        endTextLine(textActive, streamedLines);
                        System.out.println("  \033[90m↳ sub-agent " + s.agentName()
                                + " (depth " + s.depth() + ")\033[0m");
                        streamedLines[0]++;
                    }
                    case StreamEvent.SubAgentEvent se -> {
                        endTextLine(textActive, streamedLines);
                        if (inStatus[0]) {
                            System.out.print("\r\033[K");
                            inStatus[0] = false;
                        }
                        int extraLines = renderSubEvent(se, 1);
                        streamedLines[0] += extraLines;
                    }
                    case StreamEvent.SubAgentDone sd -> {
                        endTextLine(textActive, streamedLines);
                        System.out.println("  \033[90m↳ done: " + sd.agentName() + "\033[0m");
                        streamedLines[0]++;
                    }
                    case StreamEvent.ApprovalRequested ar -> {
                        if (inStatus[0]) { System.out.print("\r\033[K"); inStatus[0] = false; }
                        System.out.println("\n\033[33m⚠ approval required: tool '" + ar.toolName()
                                + "' — answer the card in the web UI to continue\033[0m");
                    }
                    case StreamEvent.SubAgentError sErr -> {
                        endTextLine(textActive, streamedLines);
                        System.out.println("  \033[90m↳ error: " + sErr.agentName()
                                + ": " + sErr.error() + "\033[0m");
                        streamedLines[0]++;
                    }
                }
            });
        } catch (Exception e) {
            System.out.println("\n[Error: " + e.getMessage() + "]");
        } finally {
            streamDone.set(true);
            cancelWatcher.interrupt();
        }
        System.out.println();
        if (cancelSent.get()) {
            System.out.println("\033[90m(cancelled)\033[0m");
        }
        if (toolCallCount[0] > 0) {
            System.out.println("\033[90m(" + toolCallCount[0] + " tool call"
                    + (toolCallCount[0] > 1 ? "s" : "") + " executed — use /tool-calls for details)\033[0m");
        }
        printContextBar(session, client);
    }

    /**
     * Spawns a daemon thread that polls {@code System.in.available()} and
     * watches for a {@code q} character followed by a newline. On match, it
     * fires a single {@code cancelChat} to the server and sets {@code cancelSent}.
     * The thread exits as soon as {@code streamDone} flips to true (or it is
     * interrupted), discarding any non-{@code q} characters it consumed.
     * <p>
     * Polling avoids the classic problem that {@code System.in.read()} blocks
     * uninterruptibly — we never block on stdin, so the next user prompt after
     * the stream finishes still works as before.
     */
    private Thread startCancelWatcher(UUID sessionId, AgentClient client,
                                       java.util.concurrent.atomic.AtomicBoolean streamDone,
                                       java.util.concurrent.atomic.AtomicBoolean cancelSent) {
        Thread t = new Thread(() -> {
            try {
                while (!streamDone.get() && !Thread.currentThread().isInterrupted()) {
                    if (System.in.available() > 0) {
                        int b = System.in.read();
                        if (b == -1) return;
                        if ((b == 'q' || b == 'Q') && !cancelSent.get()) {
                            try {
                                client.cancelChat(sessionId);
                                cancelSent.set(true);
                            } catch (Exception ex) {
                                // network blip — silently ignore, the stream will end normally
                            }
                        }
                        // Drain any trailing chars on the same line so they don't
                        // pollute the next user prompt.
                        while (System.in.available() > 0) {
                            int x = System.in.read();
                            if (x == '\n' || x == -1) break;
                        }
                    } else {
                        Thread.sleep(100);
                    }
                }
            } catch (InterruptedException | java.io.IOException ignored) {
                // ends the watcher
            }
        }, "cli-cancel-watcher");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Prints a compact one-line context bar after each agent response, e.g.:
     *   ctx  [████████░░░░░░░░░░░░]  3,241 / 8,192  39.6%  — use /compress if > 80%
     */
    private void printContextBar(AgentSession session, AgentClient client) {
        try {
            WorkingMemory stats = client.getWorkingMemory(session.id());
            if (stats.contextWindowTokens() == null) return;
            int used = stats.totalTokens();
            int max  = stats.contextWindowTokens();
            double pct = 100.0 * used / max;
            String bar   = tokenBar(used, max, 20);
            String color = pct >= 90 ? "\033[31m" : pct >= 70 ? "\033[33m" : "\033[90m";
            String reset = "\033[0m";
            String hint  = pct >= 80 ? "  \033[33m← consider /compress\033[0m" : "";
            System.out.printf("%sctx  [%s]  %,d / %,d tokens  %.1f%%%s%s%n",
                    color, bar, used, max, pct, reset, hint);
        } catch (Exception e) {
            // non-critical — silently skip if stats can't be fetched
        }
    }

    /**
     * Closes the current line of streamed agent text by emitting a newline, so a
     * subsequent status line ({@code ⏳ asking llm}, {@code ⏳ reviewing}, etc.)
     * is rendered on its own line and does not overwrite the just-streamed text.
     * No-op when no text was being printed.
     */
    private void endTextLine(boolean[] textActive, int[] streamedLines) {
        if (textActive[0]) {
            System.out.println();
            streamedLines[0]++;
            textActive[0] = false;
        }
    }

    /**
     * Renders a sub-agent event as one or more permanent lines (never as a
     * transient status line — sub-agent activity has to coexist with the
     * parent's own status updates without flicker). Recursively unwraps
     * nested {@link StreamEvent.SubAgentEvent}s, increasing the indent each
     * level. Sub-agent {@code Token} / {@code Done} / reviewer events are
     * intentionally suppressed: the sub-agent's draft answer reaches the
     * parent as a {@code tool_call_result}, so the user sees it once.
     *
     * @return number of lines emitted, so the caller can keep
     *         {@code streamedLines} accurate for any later reviewer-rewrite
     */
    private int renderSubEvent(StreamEvent.SubAgentEvent wrapper, int depth) {
        StreamEvent inner = wrapper.inner();
        // Unwrap nested SubAgentEvents — at depth 2+ the inner is itself a
        // SubAgentEvent. Each level adds indent.
        while (inner instanceof StreamEvent.SubAgentEvent next) {
            inner = next.inner();
            depth++;
        }
        String indent = "  ".repeat(depth) + "↳ ";
        switch (inner) {
            case StreamEvent.AskingLlm a -> {
                System.out.println("\033[90m" + indent + "⏳ asking llm…\033[0m");
                return 1;
            }
            case StreamEvent.ToolCallStarted s -> {
                String label = truncate(formatArgs(s.arguments()), 60);
                System.out.println("\033[90m" + indent + "⚙ " + s.toolName()
                        + (label.isEmpty() ? "" : "(" + label + ")") + "…\033[0m");
                return 1;
            }
            case StreamEvent.ToolCallResult r -> {
                System.out.println("\033[90m" + indent + "✓ " + r.toolName()
                        + " (" + r.durationMs() + "ms)\033[0m");
                return 1;
            }
            case StreamEvent.ToolCallFailed f -> {
                System.out.println("\033[90m" + indent + "✗ " + f.toolName()
                        + " (" + f.durationMs() + "ms): " + f.error() + "\033[0m");
                return 1;
            }
            case StreamEvent.SubAgentStarted s -> {
                System.out.println("\033[90m" + indent + "sub-agent " + s.agentName()
                        + " (depth " + s.depth() + ")\033[0m");
                return 1;
            }
            case StreamEvent.SubAgentDone sd -> {
                System.out.println("\033[90m" + indent + "done: " + sd.agentName() + "\033[0m");
                return 1;
            }
            case StreamEvent.SubAgentError sErr -> {
                System.out.println("\033[90m" + indent + "error: " + sErr.agentName()
                        + ": " + sErr.error() + "\033[0m");
                return 1;
            }
            // Token / Done / Reviewing / ReviewerDecision / ResponseRevised:
            // intentionally suppressed for sub-agent activity.
            default -> { return 0; }
        }
    }

    /**
     * Parses a {@code /delete-message} argument into a {@code [fromSeq, toSeq]} pair.
     * Accepted shapes (case-insensitive for the {@code last/now/end} aliases):
     * <ul>
     *   <li>{@code 5}              — single message {@code seq=5}</li>
     *   <li>{@code 1-10}           — inclusive range</li>
     *   <li>{@code 10-1}           — same as {@code 1-10} (caller normalises order)</li>
     *   <li>{@code 10-last}        — from {@code 10} to the last persisted message</li>
     *   <li>{@code 10-now} / {@code 10-end} — same as {@code 10-last}</li>
     *   <li>{@code last-2}         — the last 2 messages (i.e. {@code (last-1)-last})</li>
     *   <li>{@code last}           — only the very last message</li>
     * </ul>
     * Returns {@code null} when the input cannot be parsed.
     * <p>
     * For aliases that need to know the highest existing sequence number, the
     * server's current history is loaded once via {@code client.loadHistory}.
     */
    private int[] parseDeleteMessageRange(String arg,
                                           ai.mindconnect.agent.domain.AgentSession session,
                                           ai.mindconnect.cli.agentclient.AgentClient client) {
        if (arg == null || arg.isBlank()) return null;
        String s = arg.trim();

        // Helpers ---------------------------------------------------------
        // We resolve `last` lazily so we don't always pay a loadHistory call.
        int[] cachedLast = {Integer.MIN_VALUE};
        java.util.function.IntSupplier lastSeq = () -> {
            if (cachedLast[0] == Integer.MIN_VALUE) {
                java.util.List<ai.mindconnect.message.domain.Message> hist =
                        client.loadHistory(session.id());
                cachedLast[0] = hist.isEmpty() ? 0
                        : hist.stream().mapToInt(ai.mindconnect.message.domain.Message::sequenceNum).max().orElse(0);
            }
            return cachedLast[0];
        };

        // No dash → single seq, or bare alias "last" / "now" / "end"
        if (!s.contains("-")) {
            if (isLastAlias(s)) {
                int last = lastSeq.getAsInt();
                if (last <= 0) return null;
                return new int[]{last, last};
            }
            try {
                int n = Integer.parseInt(s);
                return new int[]{n, n};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String[] parts = s.split("-", 2);
        String left = parts[0].trim();
        String right = parts[1].trim();

        // Special: "last-N" → the last N messages (e.g. "last-2" → seq (last-1) … last)
        if (isLastAlias(left)) {
            try {
                int n = Integer.parseInt(right);
                if (n <= 0) return null;
                int last = lastSeq.getAsInt();
                if (last <= 0) return null;
                int from = Math.max(1, last - n + 1);
                return new int[]{from, last};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Generic: <int>-<int|last|now|end>
        int fromSeq;
        try {
            fromSeq = Integer.parseInt(left);
        } catch (NumberFormatException e) {
            return null;
        }
        int toSeq;
        if (isLastAlias(right)) {
            int last = lastSeq.getAsInt();
            if (last <= 0) return null;
            toSeq = last;
        } else {
            try {
                toSeq = Integer.parseInt(right);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new int[]{fromSeq, toSeq};
    }

    private static boolean isLastAlias(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("last") || t.equals("now") || t.equals("end");
    }

    private String formatArgs(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        return args.entrySet().stream()
                .map(e -> e.getValue() != null ? e.getValue().toString() : "")
                .filter(v -> !v.isEmpty())
                .findFirst().orElse("");
    }


    private void printBanner() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║     MindConnect Agent CLI  v0.1      ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Type /help for commands, /quit to exit.");
    }

    /**
     * Renders tool calls extracted from the persisted conversation, joined with
     * their TOOL_RESULT counterparts.
     * <p>
     * Modes (from {@code arg}):
     * <ul>
     *   <li>empty / no arg → tool calls since (and including) the last user message — i.e. the current turn</li>
     *   <li>{@code "all"}  → all tool calls in the conversation</li>
     *   <li>integer        → the last N tool calls</li>
     * </ul>
     */
    private void printToolCallHistory(AgentSession session, AgentClient client, String arg) {
        List<ai.mindconnect.message.domain.Message> history;
        try {
            history = client.loadHistory(session.id());
        } catch (Exception e) {
            System.out.println("[Failed to load history: " + e.getMessage() + "]");
            return;
        }
        if (history.isEmpty()) {
            System.out.println("[No messages in this session yet.]");
            return;
        }

        // Index TOOL_RESULTs by their toolCallId so we can match them with TOOL_CALL entries.
        // The TOOL_RESULT content is JSON of the form {"toolCallId":"...","toolName":"...","result":"..."}.
        java.util.Map<String, ai.mindconnect.message.domain.Message> resultsByCallId = new java.util.HashMap<>();
        for (ai.mindconnect.message.domain.Message m : history) {
            if (m.type() == ai.mindconnect.message.domain.MessageType.TOOL_RESULT) {
                String id = extractJsonField(m.content(), "toolCallId");
                if (id != null) resultsByCallId.put(id, m);
            }
        }

        // Each TOOL_CALL message wraps {"toolCalls":[{id,name,arguments}, ...]}.
        // Flatten into one entry per individual tool call, preserving conversation order.
        record Entry(int seq, java.util.UUID resultMessageId, String time, String toolName,
                     java.util.Map<String, Object> arguments, String result, Long durationMs) {}
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ai.mindconnect.message.domain.Message m : history) {
            if (m.type() != ai.mindconnect.message.domain.MessageType.TOOL_CALL) continue;
            try {
                java.util.Map<String, Object> payload = mapper.readValue(m.content(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                @SuppressWarnings("unchecked")
                java.util.List<java.util.Map<String, Object>> calls =
                        (java.util.List<java.util.Map<String, Object>>) payload.getOrDefault("toolCalls", java.util.List.of());
                for (java.util.Map<String, Object> tc : calls) {
                    String id = (String) tc.get("id");
                    String name = (String) tc.get("name");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> args =
                            (java.util.Map<String, Object>) tc.getOrDefault("arguments", java.util.Map.of());
                    ai.mindconnect.message.domain.Message resultMsg = id != null ? resultsByCallId.get(id) : null;
                    String resultText = "(no result yet)";
                    int seq = m.sequenceNum();
                    java.util.UUID resultMessageId = null;
                    Long durationMs = null;
                    if (resultMsg != null) {
                        seq = resultMsg.sequenceNum();
                        resultMessageId = resultMsg.id();
                        durationMs = resultMsg.durationMs();
                        String r = extractJsonField(resultMsg.content(), "result");
                        if (r != null) resultText = r;
                    }
                    entries.add(new Entry(seq, resultMessageId,
                            DATE_FMT.format(m.sentAt()),
                            name != null ? name : "(unknown)",
                            args, resultText, durationMs));
                }
            } catch (Exception ignored) {
                // Skip malformed TOOL_CALL payloads — no point spamming the CLI.
            }
        }

        if (entries.isEmpty()) {
            System.out.println("[No tool calls in this session yet.]");
            return;
        }

        // Decide how many to show.
        java.util.List<Entry> shown;
        String title;
        if (arg == null || arg.isEmpty()) {
            // Default: tool calls executed for the current turn — i.e. since the
            // last USER chat message. If there's no user message yet, show all.
            int lastUserSeq = history.stream()
                    .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER
                              && m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                    .mapToInt(ai.mindconnect.message.domain.Message::sequenceNum)
                    .max().orElse(0);
            shown = entries.stream().filter(e -> e.seq() >= lastUserSeq).toList();
            title = lastUserSeq > 0
                    ? "Tool calls since last user message (seq ≥ " + lastUserSeq + ")"
                    : "Tool calls (no user message yet)";
        } else if (arg.equalsIgnoreCase("all")) {
            shown = entries;
            title = "All tool calls (" + entries.size() + ")";
        } else {
            int n;
            try { n = Integer.parseInt(arg); }
            catch (NumberFormatException e) {
                System.out.println("[Usage: /tool-calls  |  /tool-calls all  |  /tool-calls <n>]");
                return;
            }
            if (n <= 0) {
                System.out.println("[Argument must be a positive integer]");
                return;
            }
            shown = entries.size() > n ? entries.subList(entries.size() - n, entries.size()) : entries;
            title = "Last " + shown.size() + " tool call(s) (of " + entries.size() + ")";
        }

        if (shown.isEmpty()) {
            System.out.println("[No tool calls in this scope.]");
            return;
        }

        System.out.println("\n── " + title + " ───────────────────────────");
        for (Entry e : shown) {
            String dur = e.durationMs() != null ? e.durationMs() + "ms" : "—";
            System.out.printf("  seq=%d  [%s]  %s  (%s)%n", e.seq(), e.time(), e.toolName(), dur);
            if (e.resultMessageId() != null) {
                System.out.printf("    msg-id  %s%n", e.resultMessageId());
            }
            e.arguments().forEach((k, v) ->
                    System.out.printf("    arg  %-12s = %s%n", k, truncate(String.valueOf(v), 80)));
            System.out.printf("    result: %s%n", truncate(e.result(), 120));
        }
        System.out.println("── End ──────────────────────────────────");
    }

    /**
     * Renders a one-line summary per message in the conversation, structured for
     * quick scanning (seq, type, role, tokens, optional duration, content preview).
     * Mirrors {@link #printToolCallHistory} but covers all message types.
     * <p>
     * Modes (from {@code arg}):
     * <ul>
     *   <li>empty / no arg → messages of the current turn (since the last user message inclusive)</li>
     *   <li>{@code "all"}  → every message in the conversation</li>
     *   <li>integer        → the last N messages</li>
     * </ul>
     */
    private void printMessageList(AgentSession session, AgentClient client, String arg) {
        List<ai.mindconnect.message.domain.Message> history;
        try {
            history = client.loadHistory(session.id());
        } catch (Exception e) {
            System.out.println("[Failed to load history: " + e.getMessage() + "]");
            return;
        }
        if (history.isEmpty()) {
            System.out.println("[No messages in this session yet.]");
            return;
        }

        java.util.List<ai.mindconnect.message.domain.Message> shown;
        String title;
        if (arg == null || arg.isEmpty()) {
            int lastUserSeq = history.stream()
                    .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER
                              && m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                    .mapToInt(ai.mindconnect.message.domain.Message::sequenceNum)
                    .max().orElse(0);
            shown = history.stream().filter(m -> m.sequenceNum() >= lastUserSeq).toList();
            title = lastUserSeq > 0
                    ? "Messages since last user message (seq ≥ " + lastUserSeq + ")"
                    : "Messages (no user message yet)";
        } else if (arg.equalsIgnoreCase("all")) {
            shown = history;
            title = "All messages (" + history.size() + ")";
        } else {
            int n;
            try { n = Integer.parseInt(arg); }
            catch (NumberFormatException e) {
                System.out.println("[Usage: /messages  |  /messages all  |  /messages <n>]");
                return;
            }
            if (n <= 0) {
                System.out.println("[Argument must be a positive integer]");
                return;
            }
            shown = history.size() > n ? history.subList(history.size() - n, history.size()) : history;
            title = "Last " + shown.size() + " message(s) (of " + history.size() + ")";
        }

        if (shown.isEmpty()) {
            System.out.println("[No messages in this scope.]");
            return;
        }

        System.out.println("\n── " + title + " ───────────────────────────");
        for (ai.mindconnect.message.domain.Message m : shown) {
            String role = m.senderType().name();
            String type = m.type().name();
            String time = DATE_FMT.format(m.sentAt());
            String tokens = m.tokenCount() != null ? m.tokenCount() + "t" : "—";
            String dur = m.durationMs() != null ? "  (" + m.durationMs() + "ms)" : "";
            String compressedTag = m.compressed() ? "  \033[33m[compressed]\033[0m" : "";
            System.out.printf("  seq=%-3d %-12s %-6s [%s]  %s%s%s%n",
                    m.sequenceNum(), type, role, time, tokens, dur, compressedTag);
            String preview = previewFor(m);
            if (!preview.isEmpty()) {
                System.out.println("    " + preview);
            }
        }
        System.out.println("── End ──────────────────────────────────");
    }

    /**
     * Builds a single-line preview for {@link #printMessageList}. CHAT shows raw
     * content; TOOL_CALL summarises the tool name(s); TOOL_RESULT shows the result
     * field if parseable, or falls back to the raw content.
     */
    private String previewFor(ai.mindconnect.message.domain.Message m) {
        String content = m.content() != null ? m.content() : "";
        switch (m.type()) {
            case TOOL_CALL: {
                try {
                    com.fasterxml.jackson.databind.JsonNode node =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
                    com.fasterxml.jackson.databind.JsonNode calls = node.get("toolCalls");
                    if (calls != null && calls.isArray() && calls.size() > 0) {
                        StringBuilder sb = new StringBuilder("→ ");
                        for (int i = 0; i < calls.size(); i++) {
                            if (i > 0) sb.append(", ");
                            com.fasterxml.jackson.databind.JsonNode c = calls.get(i);
                            sb.append(c.path("name").asText("?"));
                            com.fasterxml.jackson.databind.JsonNode args = c.path("arguments");
                            if (args.isObject() && args.size() > 0) {
                                sb.append("(");
                                int k = 0;
                                java.util.Iterator<String> fieldNames = args.fieldNames();
                                while (fieldNames.hasNext() && k < 2) {
                                    if (k > 0) sb.append(", ");
                                    String f = fieldNames.next();
                                    sb.append(f).append("=").append(truncate(args.path(f).asText(""), 40));
                                    k++;
                                }
                                if (args.size() > 2) sb.append(", …");
                                sb.append(")");
                            }
                        }
                        return truncate(sb.toString(), 140);
                    }
                } catch (Exception ignored) {}
                return truncate(content.replace('\n', ' '), 140);
            }
            case TOOL_RESULT: {
                String r = extractJsonField(content, "result");
                return truncate((r != null ? r : content).replace('\n', ' '), 140);
            }
            default:
                return truncate(content.replace('\n', ' '), 140);
        }
    }

    /**
     * Returns the value of {@code field} from a flat JSON object {@code json},
     * or {@code null} on parse error / missing field. Cheap one-shot extraction
     * using Jackson — we don't need a typed POJO for these one-off lookups.
     */
    private static String extractJsonField(String json, String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode v = node.get(field);
            return v != null && !v.isNull() ? v.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void printTools(AgentDefinition agent, AgentClient client) {
        AgentDefinition fresh = client.findAgent(agent.namespace(), agent.id()).orElse(agent);
        if (fresh.tools() == null || fresh.tools().isEmpty()) {
            System.out.println("[No tools configured for this agent.]");
            return;
        }
        System.out.println("\nTools for " + fresh.name() + ":");
        fresh.tools().stream()
                .filter(t -> t.enabled())
                .forEach(t -> System.out.println("  • " + t.name() + " — " + t.description()));
    }

    private void printMemory(AgentSession session, AgentDefinition agent, AgentClient client) {
        WorkingMemory stats = client.getWorkingMemory(session.id());

        System.out.println("\n══ MEMORY / FULL CONTEXT  [counter: " + stats.tokenCounterName() + "] ══════════════════");

        // System prompt
        System.out.println("\n── [SYSTEM PROMPT]  ~" + stats.systemPromptTokens() + "t ────────────────────────────────");
        System.out.println(stats.systemPrompt());

        if (stats.messages().isEmpty()) {
            System.out.println("\n── [NO MESSAGES IN WINDOW] ──────────────────────────────────────");
        } else {
            for (WorkingMemory.WorkingMemoryMessage m : stats.messages()) {
                java.time.Instant sentAt = java.time.Instant.ofEpochMilli(m.sentAtMs());
                String compressedTag = m.compressed() ? "  \033[33m[compressed]\033[0m" : "";
                System.out.println("\n── [" + m.type() + " / " + m.role() + "]  seq=" + m.sequenceNum()
                        + "  " + DATE_FMT.format(sentAt)
                        + "  ~" + m.tokens() + "t" + compressedTag + " ──");
                if (m.compressed() && m.compressedContent() != null) {
                    System.out.println("\033[33m" + m.compressedContent() + "\033[0m");
                    System.out.println("\033[90m[original " + m.content().length() + " chars hidden]\033[0m");
                } else {
                    System.out.println(m.content());
                }
            }
        }
        if (stats.contextWindowTokens() != null) {
            int total = stats.totalTokens();
            int max   = stats.contextWindowTokens();
            double pct = 100.0 * total / max;
            String bar = tokenBar(total, max, 20);
            String color = pct >= 90 ? "\033[31m" : pct >= 70 ? "\033[33m" : "\033[32m";
            String reset = "\033[0m";
            System.out.printf("%n══ TOTAL: ~%d / %,d tokens  %s[%s]%s  %.1f%% ══════════════%n",
                    total, max, color, bar, reset, pct);
        } else {
            System.out.println("\n══ TOTAL: ~" + stats.totalTokens() + " tokens  (context window unknown) ═══════");
        }
    }

    private void compressMemory(AgentSession session, AgentClient client) {
        System.out.println("\n⏳ Compressing memory…");
        try {
            int compressed = client.compressMemory(session.id());
            if (compressed == 0) {
                System.out.println("[Nothing to compress — conversation already fits within the context window budget.]");
            } else {
                System.out.println("✓ Compressed " + compressed + " message(s) into conversation summary.");
                System.out.println("  Raw messages are preserved. Run /memory to see the new context.");
            }
        } catch (Exception e) {
            System.out.println("[Compress failed: " + e.getMessage() + "]");
        }
    }

    private String tokenBar(int used, int max, int width) {
        int filled = (int) Math.round((double) used / max * width);
        filled = Math.min(filled, width);
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    private void printMessage(AgentSession session, int sequenceNum, AgentClient client) {
        List<Message> history = client.loadHistory(session.id());
        Message msg = history.stream()
                .filter(m -> m.sequenceNum() == sequenceNum)
                .findFirst()
                .orElse(null);
        if (msg == null) {
            System.out.println("[No message with sequenceNum=" + sequenceNum + " in this session.]");
            return;
        }
        try {
            System.out.println("\n── message seq=" + sequenceNum + " ──────────────────────────────────────");

            // Metadata header as pretty JSON (no content fields here)
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("id",                   msg.id());
            meta.put("conversationId",       msg.conversationId());
            meta.put("senderId",             msg.senderId());
            meta.put("senderType",           msg.senderType());
            meta.put("recipientId",          msg.recipientId());
            meta.put("type",                 msg.type());
            meta.put("sequenceNum",          msg.sequenceNum());
            meta.put("sentAt",               msg.sentAt().toString());
            meta.put("tokenCount",           msg.tokenCount());
            meta.put("compressed",           msg.compressed());
            meta.put("compressedTokenCount", msg.compressedTokenCount());
            if (msg.metadata() != null && !msg.metadata().isEmpty()) {
                meta.put("metadata", msg.metadata());
            }
            System.out.println(PRETTY_MAPPER.writeValueAsString(meta));

            // Content — printed raw so \n renders as real newlines.
            // If it parses as JSON, pretty-print the JSON; otherwise print the string as-is.
            System.out.println("\n── content ──");
            printFieldValue(msg.content());

            if (msg.compressedContent() != null) {
                System.out.println("\n── compressedContent ──");
                printFieldValue(msg.compressedContent());
            }

            System.out.println("────────────────────────────────────────────────────────────");
        } catch (Exception e) {
            System.out.println("[Failed to render message: " + e.getMessage() + "]");
        }
    }

    /**
     * Prints a field value.
     * <ul>
     *   <li>If the value is a JSON object containing a {@code "result"} key (TOOL_RESULT payload),
     *       the envelope is printed as pretty JSON and then the result string is printed separately
     *       so that {@code \n} sequences render as real newlines.</li>
     *   <li>Otherwise, if the value is valid JSON it is pretty-printed.</li>
     *   <li>Otherwise the raw string is printed, which also renders {@code \n} as real newlines.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void printFieldValue(String value) {
        try {
            Object parsed = PRETTY_MAPPER.readValue(value, Object.class);
            if (parsed instanceof java.util.Map<?, ?> map && map.containsKey("result")) {
                // TOOL_RESULT envelope — print without the result field, then result separately
                java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) map);
                String result = (String) envelope.remove("result");
                if (!envelope.isEmpty()) {
                    System.out.println(PRETTY_MAPPER.writeValueAsString(envelope));
                }
                System.out.println("\n── result ──");
                System.out.println(result != null ? result : "(null)");
            } else {
                System.out.println(PRETTY_MAPPER.writeValueAsString(parsed));
            }
        } catch (Exception ignored) {
            // Plain string — print as-is so \n renders as real newlines
            System.out.println(value);
        }
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  <message>   — send a message to the current agent");
        System.out.println("  /tools      — list tools available to this agent");
        System.out.println("  /tool-calls                    — show tool calls for the current turn (since last user message)");
        System.out.println("  /tool-calls all                — show all tool calls in this session");
        System.out.println("  /tool-calls <n>                — show the last <n> tool calls");
        System.out.println("  /messages                      — show messages for the current turn (since last user message)");
        System.out.println("  /messages all                  — show all messages in this session");
        System.out.println("  /messages <n>                  — show the last <n> messages");
        System.out.println("  /history                       — show the conversation (CHAT messages only)");
        System.out.println("  /history <n>                   — show only the last <n> CHAT pairs");
        System.out.println("  /message <seq>                 — show full details of a message by sequence number");
        System.out.println("  /delete-message <seq>          — delete a single message");
        System.out.println("  /delete-message <from>-<to>    — delete messages in sequence range (inclusive); 10-1 == 1-10");
        System.out.println("  /delete-message <from>-last    — delete from <from> to end (also: -now, -end)");
        System.out.println("  /delete-message last-<n>       — delete the last <n> messages");
        System.out.println("  /memory                        — show full context: system prompt + current window + token usage");
        System.out.println("  /compress   — compress all messages into summary, clear working memory");
        System.out.println("  /new        — start a new session (keep agent)");
        System.out.println("  /sessions   — list and switch to another session");
        System.out.println("  /back       — return to agent selection");
        System.out.println("  /quit       — exit");
    }

    /**
     * Pre-selected entry point computed from the persisted CLI state.
     * If {@link #session} is non-null the main loop drops the user straight into chat.
     * If only {@link #agent} is set the user lands in the session picker for that agent.
     * Both null = normal start at the agent list.
     */
    private record Resume(AgentDefinition agent, AgentSession session) {
        static Resume none() { return new Resume(null, null); }
    }

    private Resume resolveResume(CliState state, Namespace namespace, String userId, AgentClient client) {
        if (state == null || state.view() == CliState.View.AGENT_LIST || state.lastAgentId() == null) {
            return Resume.none();
        }
        Optional<AgentDefinition> agentOpt = client.findAgent(namespace, state.lastAgentId());
        if (agentOpt.isEmpty()) {
            // Stale agent — reset and fall back to the main menu.
            stateStore.save(CliState.agentList());
            return Resume.none();
        }
        AgentDefinition agent = agentOpt.get();
        if (state.view() == CliState.View.SESSION_LIST || state.lastSessionId() == null) {
            return new Resume(agent, null);
        }
        UUID wantedId = state.lastSessionId();
        AgentSession session = client.listSessions(agent.id(), namespace, userId).stream()
                .filter(s -> s.id().equals(wantedId))
                .findFirst()
                .orElse(null);
        if (session == null) {
            // Stale session — per spec, fall back to the main menu (not the session list).
            stateStore.save(CliState.agentList());
            return Resume.none();
        }
        return new Resume(agent, session);
    }
}
