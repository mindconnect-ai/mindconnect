package ai.mindconnect.webscraper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
public class HtmlCleaner {

    public static String cleanHtml(String htmlContent) {
        // Parse the HTML content using Jsoup
        Document document = Jsoup.parse(htmlContent);

        // Remove comments
        document.getAllElements().forEach(element -> {
            if (element.nodeName().equals("#comment")) {
                element.remove();
            }
        });

        // Remove non-informative tags and empty tags
        document.select("script, style, meta, link, nav, aside, footer, iframe").forEach(Element::remove);

        // Remove all attributes
        for (Element element : document.getAllElements()) {
            element.clearAttributes();
            //element.attributes().asList().stream().forEach(a -> a.);
        }
        // Condense whitespace
        String text = document.wholeText().replaceAll("\\s+", " ").trim();

        // Return the cleaned HTML
        return text;
    }

    public static String extractTextOnlyFromHtml(String htmlContent) {
        // Parse the HTML content using Jsoup
        Document document = Jsoup.parse(htmlContent);

        // Use a StringBuilder to build the text content with newlines
        StringBuilder textContent = new StringBuilder();

        // Extract and append text content for block-level elements
        for (Element element : document.body().select("*")) {
            if (isBlockLevelElement(element)) {
                String text = element.ownText().trim();
                if (!text.isEmpty()) {
                    textContent.append(text).append("\n\n");
                }
            }
        }

        // Return the cleaned text
        return textContent.toString().trim();
    }

    // Helper method to determine if an element is block-level
    private static boolean isBlockLevelElement(Element element) {
        // Block-level tags: Adjust this list as needed
        String tagName = element.tagName();
        return tagName.matches("p|div|h1|h2|h3|h4|h5|h6|li|ul|ol|blockquote|pre");
    }
}
