package ai.mindconnect.webscraper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class BaseWebScraperContext {

    public static interface SaveFunction {
        void save(String directory, ScrapedPage page) throws IOException;
    }

    public static class DefaultSaveFunction implements SaveFunction{

        private final ObjectMapper objectMapper;

        public DefaultSaveFunction(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public void save(String directory, ScrapedPage page) throws IOException {
            String fileName = getFileName(page);
            page.setFilePath(directory + "/" + fileName);
            String content = this.objectMapper.writeValueAsString(page);
            saveFile(directory, fileName, content);
        }

        protected void saveFile(String directory, String fileName, String content)
                throws IOException {
            File dir = new File(directory);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, fileName);
            Files.writeString(file.toPath(), content);
        }
    }

    protected ObjectMapper objectMapper;
    protected BaseWebScraperOptions options;
    private Set<String> visitedUrls = new LinkedHashSet<>();
    @JsonIgnore
    private SaveFunction saveFunction = null;
    public BaseWebScraperContext(BaseWebScraperOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .registerModule(new JavaTimeModule());
        this.saveFunction = new DefaultSaveFunction(this.objectMapper);
    }

    public void visited(String url) {
        this.visitedUrls.add(url);
    }

    public boolean hasAlreadyVisited(String url) {
        return this.visitedUrls.contains(url);
    }

    public boolean shouldVisit(String urlToVisit) {
        return !hasAlreadyVisited(urlToVisit);
    }

    @SneakyThrows
    public void save(String directory, ScrapedPage page) {
        if (this.saveFunction != null) {
            this.saveFunction.save(directory, page);
        }
    }

    @NotNull
    public static String getFileName(ScrapedPage page) {
        String fileName = page.getUrl().replace("http://", "");
        fileName = fileName.replace("https://", "");
        fileName = fileName.replace("/", "_");
        fileName = fileName.replace("?", "_");
        fileName = fileName.replace("#", "_");
        fileName = fileName.replace("x", "_");
        fileName = fileName.replace("=", "-");
        fileName = fileName.replace(" ", "-");
        fileName = fileName.replace("+", "-");
        fileName = fileName.replace("&", "-");
        return fileName + ".json";
    }
}
