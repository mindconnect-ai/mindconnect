package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.protocol.item.Role;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live examples for OpenAI's HOSTED capabilities through the protocol:
 * web search, code execution, and asking questions against an uploaded file.
 * Runs only when mc.env (or the environment) provides an OPENAI_API_KEY.
 *
 * <p>Live-model examples are inherently nondeterministic — a rare red run
 * usually means the model answered off-script, not that the adapter broke.
 *
 * <p>These four scenarios are the parity target for the runtime backend:
 * there, web/code/file become REGISTERED tools of the agent definition
 * (mc-agent-tools-web, mc-agent-tools-code, file store + RAG) and show up
 * as the same FunctionCall/Output item pairs — hosted vs. registered is a
 * backend detail the protocol hides.
 */
@EnabledIf("openAiEnabled")
class OpenAiHostedToolsExampleTest {

    static boolean openAiEnabled() {
        return TestOpenAi.enabled();
    }

    private final OpenAiResponsesBackend backend =
            new OpenAiResponsesBackend(TestOpenAi.apiKey());

    // ── 1. Hosted web search ────────────────────────────────────────────────

    @Test
    void webSearch() {
        backend.register(PseudoAgent.of("researcher", TestOpenAi.model(),
                        "Answer briefly. You MUST use web search for current facts.")
                .withHostedTool("web_search"));
        Session session = backend.openSessionForAgent("examples", "researcher");

        Response r = backend.create(ResponseRequest.text(session.id(),
                "Search the web: what is today's top headline from any major news site?"));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isNotBlank();
        // the hosted call is visible as an item — executed INSIDE OpenAI:
        assertThat(itemTypes(r)).contains("web_search_call");
        System.out.println("[web_search] " + r.outputText());
    }

    // ── 2. Hosted code execution ────────────────────────────────────────────

    @Test
    void codeExecution() {
        backend.register(PseudoAgent.of("analyst", TestOpenAi.model(),
                        "Use the code interpreter for every calculation. Show only the result.")
                .withHostedTool(Map.of("type", "code_interpreter",
                        "container", Map.of("type", "auto")))
                .withToolChoice("required"));
        Session session = backend.openSessionForAgent("examples", "analyst");

        // Not memorizable — the model MUST actually run code for this:
        Response r = backend.create(ResponseRequest.text(session.id(),
                "Compute the SHA-256 hex digest of the ASCII string 'mindconnect' with Python."));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).contains("c035caf4ab7f39e2");
        assertThat(itemTypes(r)).contains("code_interpreter_call");
        System.out.println("[code] " + r.outputText());
    }

    // ── 3. Upload a file, ask against it (document understanding) ──────────

    @Test
    void uploadPdfAndAskAgainstIt() {
        backend.register(PseudoAgent.of("reader", TestOpenAi.model(),
                "Answer strictly from the attached document."));
        Session session = backend.openSessionForAgent("examples", "reader");

        StoredFile file = backend.files().upload("secret.pdf", "application/pdf",
                minimalPdf("The secret code is MC-4711"));

        var question = new ConversationItem.Message(Role.USER, List.of(
                new ContentPart.Text("What is the secret code in this document?"),
                new ContentPart.Document(new ContentPart.MediaSource.FileId(file.id()), "secret.pdf")));
        Response r = backend.create(new ResponseRequest(session.id(), List.of(question), false, List.of()));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).contains("4711");
        System.out.println("[file q&a] " + r.outputText());
    }

    // ── 4. Upload data, analyze it with executed code ───────────────────────

    @Test
    void uploadCsvAndAnalyzeWithCode() {
        StoredFile csv = backend.files().upload("sales.csv", "text/csv",
                "region,revenue\nnorth,10\nsouth,20\nwest,30\n".getBytes(StandardCharsets.UTF_8));

        // multi-step file work: the larger model finishes the plan reliably
        backend.register(PseudoAgent.of("data-analyst", TestOpenAi.model(),
                        "Analyze the attached files with Python. Report only the number.")
                .withHostedTool(Map.of("type", "code_interpreter",
                        "container", Map.of("type", "auto", "file_ids", List.of(csv.id()))))
                .withToolChoice("required"));
        Session session = backend.openSessionForAgent("examples", "data-analyst");

        Response r = backend.create(ResponseRequest.text(session.id(),
                "Sum the revenue column of sales.csv."));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).contains("60");
        System.out.println("[csv analysis] " + r.outputText());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Hosted calls surface as FunctionCall items whose name is the OpenAI type. */
    private static List<String> itemTypes(Response r) {
        return r.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.FunctionCall.class::isInstance)
                .map(item -> ((ConversationItem.FunctionCall) item).name())
                .toList();
    }

    /**
     * A minimal but valid single-page PDF containing {@code text} — offsets
     * computed, ASCII only, so the example has no PDF-library dependency.
     */
    private static byte[] minimalPdf(String text) {
        String stream = "BT /F1 18 Tf 72 720 Td (" + text + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        long[] offsets = new long[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = pdf.length();
            pdf.append(i + 1).append(" 0 obj ").append(objects.get(i)).append(" endobj\n");
        }
        long xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            pdf.append(String.format("%010d 00000 n \n", offsets[i]));
        }
        pdf.append("trailer << /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
