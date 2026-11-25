package ai.mindconnect.agent.cli.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.sdk.AgentRuntime;

/**
 * The conversation on the terminal: reads a line, submits it as a turn,
 * prints what the agent says while it says it, and when the turn comes back
 * with questions — may this tool run, what does the user say — asks them
 * and resumes the turn with the answers.
 */
public final class LocalCli {

    private static final String EXIT = "/exit";
    private static final long POLL_MILLIS = 50;

    private final AgentRuntime runtime;
    private final Session session;
    private final BufferedReader in;
    private final PrintStream out;

    public LocalCli(AgentRuntime runtime, Session session, BufferedReader in, PrintStream out) {
        this.runtime = runtime;
        this.session = session;
        this.in = in;
        this.out = out;
    }

    /** Talks until the input ends or the user types {@code /exit}. */
    public void converse() {
        out.print("> ");
        for (String line = readLine(); line != null && !EXIT.equals(line.strip()); line = readLine()) {
            if (!line.isBlank()) {
                ask(line);
            }
            out.print("> ");
        }
    }

    /** One turn, from the user's line to the agent's answer, with every question in between. */
    public void ask(String text) {
        TurnId turnId = runtime.turns().submit(TurnRequest.text(session.id(), text));
        AtomicBoolean streamed = new AtomicBoolean();
        try (Subscription following = runtime.turns().subscribe(turnId, event -> {
            // The turn's own answer as it is written; what a sub-agent writes is not the answer.
            if (turnId.equals(event.turnId())) {
                event.as(StreamEvent.TOKEN).ifPresent(token -> {
                    streamed.set(true);
                    out.print(token.text());
                    out.flush();
                });
            }
        })) {
            Turn turn = awaitRest(turnId);
            while (turn.status() == TurnStatus.INTERRUPTED) {
                runtime.turns().resume(turnId, answers(turn.interruptions()));
                turn = awaitResumed(turnId, turn.interruptions());
            }
            report(turn, streamed.get());
        }
    }

    private void report(Turn turn, boolean streamed) {
        if (turn.status() == TurnStatus.COMPLETED || turn.status() == TurnStatus.INCOMPLETE) {
            // A model that does not stream has said nothing yet.
            out.println(streamed ? "" : turn.outcome().text());
        }
        if (turn.status() != TurnStatus.COMPLETED) {
            out.println("[" + turn.status() + (turn.error() == null ? "" : ": " + turn.error()) + "]");
        }
    }

    private List<InterruptionAnswer> answers(List<Interruption> interruptions) {
        List<InterruptionAnswer> answers = new ArrayList<>();
        for (Interruption interruption : interruptions) {
            out.println();
            answers.add(Interruption.TOOL_APPROVAL.equals(interruption.kind())
                    ? approval(interruption)
                    : InterruptionAnswer.text(interruption.id(), question(interruption)));
        }
        return answers;
    }

    private InterruptionAnswer approval(Interruption interruption) {
        out.print("Allow " + interruption.call().toolName() + " " + interruption.call().arguments()
                + "? [y]es / [a]lways / [n]o: ");
        String said = String.valueOf(readLine()).strip().toLowerCase();
        if (said.startsWith("a")) {
            return InterruptionAnswer.approveAlways(interruption.id());
        }
        return said.startsWith("y")
                ? InterruptionAnswer.approve(interruption.id())
                : InterruptionAnswer.reject(interruption.id(), "the user said no");
    }

    private String question(Interruption interruption) {
        Object asked = interruption.details().get("question");
        out.print((asked == null ? interruption.kind() + " " + interruption.details() : asked) + " ");
        return String.valueOf(readLine());
    }

    /** Waits until the turn stands still: ended, or interrupted and waiting for an answer. */
    private Turn awaitRest(TurnId turnId) {
        Turn turn = runtime.turns().find(turnId).orElseThrow();
        while (!turn.status().isFinal() && turn.status() != TurnStatus.INTERRUPTED) {
            pause();
            turn = runtime.turns().find(turnId).orElseThrow();
        }
        return turn;
    }

    /** After a resume the turn still reads as interrupted until a worker takes the answers up: wait for that first. */
    private Turn awaitResumed(TurnId turnId, List<Interruption> answered) {
        Turn turn = awaitRest(turnId);
        while (turn.status() == TurnStatus.INTERRUPTED && turn.interruptions().equals(answered)) {
            pause();
            turn = awaitRest(turnId);
        }
        return turn;
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the turn", e);
        }
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
