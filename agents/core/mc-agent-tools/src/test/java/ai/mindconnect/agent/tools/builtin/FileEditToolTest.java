package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** file_edit: one unique passage becomes another, the diff says what changed, ambiguity is refused. */
class FileEditToolTest {

    @TempDir
    Path tmp;

    private static final String SOURCE = """
            package demo;

            class Greeter {
                String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """;

    @Test
    void aUniquePassageIsReplaced_andTheDiffComesBack() throws Exception {
        Files.writeString(tmp.resolve("Greeter.java"), SOURCE);
        FileEditTool edit = new FileEditTool(tmp);

        String out = edit.execute(Map.of("path", "Greeter.java",
                "old_string", "        return \"Hello, \" + name;",
                "new_string", "        return \"Hi, \" + name + \"!\";"));

        assertThat(out).startsWith("Edited Greeter.java\n--- Greeter.java\n+++ Greeter.java\n@@ -2,6 +2,6 @@\n")
                .contains("\n-        return \"Hello, \" + name;\n+        return \"Hi, \" + name + \"!\";\n")
                .contains("\n class Greeter {\n").contains("\n }");
        assertThat(Files.readString(tmp.resolve("Greeter.java")))
                .contains("return \"Hi, \" + name + \"!\";").doesNotContain("Hello");
    }

    @Test
    void anAmbiguousPassageIsRefused_unlessReplaceAll() throws Exception {
        Files.writeString(tmp.resolve("t.txt"), "a\nfoo\nb\nfoo\nc\n");
        FileEditTool edit = new FileEditTool(tmp);

        assertThat(edit.execute(Map.of("path", "t.txt", "old_string", "foo", "new_string", "bar")))
                .isEqualTo("Error: old_string occurs 2 times in t.txt. Include more of the surrounding lines "
                        + "so it is unique, or pass replace_all=true to change every occurrence.");
        assertThat(Files.readString(tmp.resolve("t.txt"))).as("nothing written").isEqualTo("a\nfoo\nb\nfoo\nc\n");

        String out = edit.execute(Map.of("path", "t.txt", "old_string", "foo", "new_string", "bar",
                "replace_all", true));
        assertThat(out).startsWith("Replaced 2 occurrences in t.txt\n").contains("-foo\n+bar\n");
        assertThat(Files.readString(tmp.resolve("t.txt"))).isEqualTo("a\nbar\nb\nbar\nc\n");
    }

    @Test
    void whatIsNotThereIsAnError_andSoAreMissingArgumentsAndForeignPaths() throws Exception {
        Files.writeString(tmp.resolve("t.txt"), "hello\n");
        Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(tmp.resolve("outside/o.txt"), "x");
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("p.txt"), "hello\n");
        FileEditTool edit = new FileEditTool(FileRoots.of(project.toString(), List.of()));

        assertThat(edit.execute(Map.of("path", "p.txt", "old_string", "bye", "new_string", "x")))
                .startsWith("Error: old_string was not found in p.txt.");
        assertThat(edit.execute(Map.of("path", "p.txt", "old_string", "hello", "new_string", "hello")))
                .startsWith("Error: old_string and new_string are the same");
        assertThat(edit.execute(Map.of("path", "p.txt", "old_string", "", "new_string", "x")))
                .startsWith("Error: old_string is required");
        assertThat(edit.execute(Map.of("path", "nope.txt", "old_string", "a", "new_string", "b")))
                .startsWith("Error: file does not exist: nope.txt");
        assertThat(edit.execute(Map.of("path", tmp.resolve("outside/o.txt").toString(),
                "old_string", "x", "new_string", "y")))
                .startsWith("Error: path is outside the allowed directories");
        assertThat(edit.execute(Map.of("path", "p.txt", "old_string", "hello\n", "new_string", "")))
                .as("an empty new_string deletes the passage").startsWith("Edited p.txt");
        assertThat(Files.readString(project.resolve("p.txt"))).isEmpty();
    }

    @Test
    void theDiffHasOneHunkPerChange_withThreeLinesOfContext() {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n";
        String after = "1\n2\n3\nX\n5\n6\n7\n8\n9\n10\n11\n12\n13\nY\n15\n";

        String diff = FileEditTool.unifiedDiff("n", before, after);

        assertThat(diff).isEqualTo("""
                --- n
                +++ n
                @@ -1,7 +1,7 @@
                 1
                 2
                 3
                -4
                +X
                 5
                 6
                 7
                @@ -11,5 +11,5 @@
                 11
                 12
                 13
                -14
                +Y
                 15""");
        assertThat(FileEditTool.unifiedDiff("n", "a\n", "a\nb\n")).endsWith("@@ -1,1 +1,2 @@\n a\n+b");
    }

    @Test
    void indentationTheModelGotWrongIsForgiven_andTheFilesOwnIsKept() throws Exception {
        Files.writeString(tmp.resolve("Svc.java"), "class Svc {\n    void run() {\n        go();\n    }\n}\n");
        FileEditTool edit = new FileEditTool(tmp);

        String out = edit.execute(Map.of("path", "Svc.java",
                "old_string", "void run() {\n  go();\n}",
                "new_string", "void run() {\n  prepare();\n  go();\n}"));

        assertThat(out).startsWith("Edited Svc.java");
        assertThat(Files.readString(tmp.resolve("Svc.java")))
                .isEqualTo("class Svc {\n    void run() {\n        prepare();\n        go();\n    }\n}\n");
    }

    @Test
    void aPassageThatIsNotThereShowsTheClosestOne() throws Exception {
        Files.writeString(tmp.resolve("app.ts"), "import x from 'x';\n\nasync function sendToApi(text: string) {\n"
                + "  const res = await fetch(url);\n  return res.json();\n}\n\nexport {};\n");
        FileEditTool edit = new FileEditTool(tmp);

        String out = edit.execute(Map.of("path", "app.ts",
                "old_string", "async function sendToApi(userText: string): Promise<string> {\n  const r = await fetch(url);",
                "new_string", "x"));

        assertThat(out).startsWith("Error: old_string was not found in app.ts.")
                .contains("The closest passage in the file (lines 1-6):")
                .contains("3\tasync function sendToApi(text: string) {")
                .contains("4\t  const res = await fetch(url);");
        assertThat(edit.execute(Map.of("path", "app.ts", "old_string", "nothing like this anywhere zzz", "new_string", "x")))
                .doesNotContain("closest passage");
    }
}
