package ai.mindconnect.webscraper;

import ai.mindconnect.webscraper.jsoup.JsoupWebScraper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebScraperMain {

    public static void main(String[] args) throws Exception {
        //String startUrl = "https://help.communityfibre.co.uk/troubleshooting";
        String startUrl = "https://www.placetel.de/funktionen";
        WebScraperOptions options = new WebScraperOptions();
        options.setUrl(startUrl);
        options.setIgnoreErrors(true);
        options.addIncludePattern(startUrl, UrlPattern.Mode.STARTSWITH);
        options.addCssSelector(".content-body");
        options.setLinkDepth(1);
        options.setIncludeChildren(true);
        options.setExtractContent(true);
        options.setConvertToMarkdown(true);
        options.setRemoveNewLine(true);
        options.setRemoveHtml(true);
        options.setSaveToDirectory("/Users/dbe/Insync/deep_tc/gdrive/Business/Coderepo/mindconnect/mc-java/core/mc-webscraper/src/main/resources/export");

        JsoupWebScraper scraper = new JsoupWebScraper();
        var results = scraper.scrape(List.of(options));

//        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
//        Map<String, Object> map = new HashMap<>();
//        //File file = new File("community-fiber-help.json");
//        File file = new File("placetel.json");
//        if (file.exists()) {
//            file.delete();
//        }
//        mapper.writeValue(file, results);
    }
}
