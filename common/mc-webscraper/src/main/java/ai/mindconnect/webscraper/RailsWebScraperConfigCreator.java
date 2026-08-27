package ai.mindconnect.webscraper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RailsWebScraperConfigCreator {

    public static void main(String[] args) throws Exception {

        //        var linkList = readUrlListFromFile("/placetel_funktionen.txt", null);
        //        RailsWebScraperConfig config = createForLinkListContext("funktionen","summarize_article", linkList);
        //        printAsJSON(config);

        //
        //var config = createForGetLinksList("https://www.placetel.de/hilfe/webex-fuer-placetel");
        //printAsJSON(config);

//        var linkList = readUrlListFromFile(
//                "/webex-fuer-placetel.txt",
//                (url) -> url.startsWith("https://www.placetel.de/hilfe/webex-fuer-placetel")
//        );
//        System.out.println(linkList.stream().collect(Collectors.joining("\n")));
//        RailsWebScraperConfig config = createForLinkListContext("webex-fuer-placetel","summarize_article", linkList);
//        printAsJSON(config);

//        var linkList = readUrlListFromFile(
//                "/produkt.txt",null
//        );
//        System.out.println(linkList.stream().collect(Collectors.joining("\n")));
//        RailsWebScraperConfig config = createForLinkListContext("produkt","summarize_article", linkList);
//        printAsJSON(config);

//        var linkList = readUrlListFromFile(
//                "/zielgruppen.txt",null
//        );
//        System.out.println(linkList.stream().collect(Collectors.joining("\n")));
//        RailsWebScraperConfig config = createForLinkListContext("zielgruppen","summarize_article", linkList);
//        printAsJSON(config);

//        var linkList = readUrlListFromFile(
//                "/misc_infos.txt",null
//        );
//        System.out.println(linkList.stream().collect(Collectors.joining("\n")));
//        RailsWebScraperConfig config = createForLinkListContext("misc_infos","summarize_article", linkList);
//        printAsJSON(config);

        var linkList = readUrlListFromFile(
                "/rufnummern.txt",null
        );
        System.out.println(linkList.stream().collect(Collectors.joining("\n")));
        RailsWebScraperConfig config = createForLinkListContext("rufnummern","summarize_article", linkList);
        printAsJSON(config);
    }

    private static void printAsJSON(RailsWebScraperConfig config) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        System.out.println(mapper.writeValueAsString(config));
    }

    @NotNull
    private static Set<String> readUrlListFromFile(String filePath, Predicate<String> filter)
            throws IOException {
        Path path = Paths.get(RailsWebScraperConfigCreator.class.getResource(filePath).getPath());
        String urls = Files.readString(path);
        var linkList = cleanupLinkList(urls, filter);
        return linkList;
    }

    public static Set<String> cleanupLinkList(String listOfUrls, Predicate<String> filter) {
        String[] urls = listOfUrls.split(";");
        Set<String> result = new TreeSet<>();
        for (String url : urls) {
            String curUrl = url.trim();
            if (curUrl.length() > 0 && (filter == null || (filter != null && filter.test(curUrl)))

            ) {
                if (!curUrl.endsWith("#")) {
                    result.add(curUrl);
                }
            }
        }
        return result;
    }

    public static RailsWebScraperConfig createForLinkListContext(
            String contextParent,
            String summaryPrompt,
            Set<String> linkList
    ) {
        return RailsWebScraperConfig.builder()
                .run_as_job(0)
                .scan_depth(0)
                .add_content(1)
                .target(RailsWebScraperConfig.Target.context)
                .context_parent(contextParent)
                .summary_mode(true)
                .summary_prompt(summaryPrompt)
                .scrap_below_url(linkList.stream().findFirst().get())
                .content_scope("body")
                .extract(".content-body")
                .remove_html(1)
                .remove_newline(1)
                .extract_header(1)
                .header("h1")
                .extract_youtube(1)
                .enable_web_page_list(1)
                .web_page_list(linkList.stream().collect(Collectors.joining(";")))
                .build();
    }

    public static RailsWebScraperConfig createForGetLinksList(String parentUrl) {
        return RailsWebScraperConfig.builder()
                .run_as_job(0)
                .scan_depth(1)
                .add_content(0)
                .target(RailsWebScraperConfig.Target.context)
                .context_parent("")
                .summary_prompt("")
                .scrap_below_url(parentUrl)
                .content_scope("body")
                .extract(".content-body")
                .remove_html(1)
                .remove_newline(1)
                .extract_header(1)
                .header("h1")
                .extract_links(1)
                .extract_youtube(1)
                .enable_web_page_list(0)
                .web_page_list("")
                .build();
    }
}
