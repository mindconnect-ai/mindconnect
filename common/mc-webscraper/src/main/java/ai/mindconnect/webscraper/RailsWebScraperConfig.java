package ai.mindconnect.webscraper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RailsWebScraperConfig {

    public enum Target {
        context, vector
    }

    private int run_as_job = 0;
    private int scan_depth = 1;
    private int add_content = 1;
    private Target target = Target.context;
    private int simulate_add = 0;
    private String context_parent = "funktionen";
    private boolean summary_mode =  true;
    private String summary_prompt = "summarize_article";
    private int extract_links =  1;
    private int enable_scrap_below =  1;
    private String scrap_below_url =  "https://www.placetel.de/funktionen";
    private String content_scope =  "body";
    private String extract =  ".content-body";
    private int remove_html =  1;
    private int remove_newline =  1;
    private int extract_header =  1;
    private String header =  "h1";
    private int extract_youtube =  1;
    private int enable_web_page_list =  0;
    private String web_page_list = "";
}
