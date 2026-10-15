package ai.mindconnect.agent.tools.document;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public class HtmlToMarkdown {

    public static String convert(Document doc) {
        // remove noise before converting
        doc.select("nav, footer, header, script, style, noscript, iframe, aside, " +
                   "form, button, input, select, textarea, figure > figcaption, svg").remove();

        StringBuilder sb = new StringBuilder();
        visitChildren(doc.body(), sb, 0);
        return cleanup(sb.toString());
    }

    private static void visitChildren(Element parent, StringBuilder sb, int listDepth) {
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode text) {
                String t = text.text();
                if (!t.isBlank()) sb.append(t);
            } else if (node instanceof Element el) {
                visitElement(el, sb, listDepth);
            }
        }
    }

    private static void visitElement(Element el, StringBuilder sb, int listDepth) {
        switch (el.tagName()) {
            case "h1" -> block(sb, "# " + el.text());
            case "h2" -> block(sb, "## " + el.text());
            case "h3" -> block(sb, "### " + el.text());
            case "h4" -> block(sb, "#### " + el.text());
            case "h5", "h6" -> block(sb, "##### " + el.text());

            case "p", "div", "section", "article", "main" -> {
                sb.append("\n\n");
                visitChildren(el, sb, listDepth);
                sb.append("\n\n");
            }

            case "br" -> sb.append("  \n");

            case "a" -> {
                String text = el.text().strip();
                String href = el.attr("abs:href");
                if (text.isEmpty()) {
                    // skip empty links
                } else if (href.isEmpty() || href.equals(text)) {
                    sb.append(text);
                } else {
                    sb.append("[").append(text).append("](").append(href).append(")");
                }
            }

            case "strong", "b" -> {
                String t = el.text().strip();
                if (!t.isEmpty()) sb.append("**").append(t).append("**");
            }

            case "em", "i" -> {
                String t = el.text().strip();
                if (!t.isEmpty()) sb.append("*").append(t).append("*");
            }

            case "code" -> {
                String t = el.text();
                if (!t.isEmpty()) sb.append("`").append(t).append("`");
            }

            case "pre" -> {
                String code = el.text();
                sb.append("\n\n```\n").append(code).append("\n```\n\n");
            }

            case "blockquote" -> {
                sb.append("\n\n");
                for (String line : el.text().split("\n")) {
                    sb.append("> ").append(line).append("\n");
                }
                sb.append("\n");
            }

            case "ul" -> {
                sb.append("\n");
                for (Element li : el.select("> li")) {
                    sb.append("  ".repeat(listDepth)).append("- ");
                    visitChildren(li, sb, listDepth + 1);
                    sb.append("\n");
                }
                sb.append("\n");
            }

            case "ol" -> {
                sb.append("\n");
                int i = 1;
                for (Element li : el.select("> li")) {
                    sb.append("  ".repeat(listDepth)).append(i++).append(". ");
                    visitChildren(li, sb, listDepth + 1);
                    sb.append("\n");
                }
                sb.append("\n");
            }

            case "table" -> {
                sb.append("\n\n");
                for (Element row : el.select("tr")) {
                    sb.append("| ");
                    for (Element cell : row.select("th, td")) {
                        sb.append(cell.text().strip()).append(" | ");
                    }
                    sb.append("\n");
                    // header separator after first row
                    if (!row.select("th").isEmpty()) {
                        sb.append("| ");
                        for (int i = 0; i < row.select("th").size(); i++) {
                            sb.append("--- | ");
                        }
                        sb.append("\n");
                    }
                }
                sb.append("\n");
            }

            case "hr" -> sb.append("\n\n---\n\n");

            case "img" -> {
                String alt = el.attr("alt").strip();
                String src = el.attr("abs:src");
                if (!alt.isEmpty() && !src.isEmpty()) {
                    sb.append("![").append(alt).append("](").append(src).append(")");
                }
            }

            // skip purely presentational / metadata tags
            case "head", "meta", "link", "svg", "path", "canvas",
                 "noscript", "script", "style" -> { /* ignore */ }

            default -> visitChildren(el, sb, listDepth);
        }
    }

    private static void block(StringBuilder sb, String text) {
        sb.append("\n\n").append(text).append("\n\n");
    }

    private static String cleanup(String raw) {
        return raw
                .replaceAll("[ \\t]+", " ")           // collapse horizontal whitespace
                .replaceAll(" *\n *", "\n")            // trim spaces around newlines
                .replaceAll("\n{3,}", "\n\n")          // max two consecutive blank lines
                .strip();
    }
}
