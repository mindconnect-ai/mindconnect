package ai.mindconnect.webscraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;

import java.io.IOException;

@Data
public class ApplicationConfig {

    PineconeConfig pinecone;
    OpenAIConfig openai;

    @Data
    public static class PineconeConfig {
        String secretKey = System.getenv("PINECONE_API_KEY");
        String environment = System.getenv().getOrDefault("PINECONE_ENVIRONMENT", "");
        String host = System.getenv().getOrDefault("PINECONE_HOST", "");
        String index = System.getenv().getOrDefault("PINECONE_INDEX", "");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "");
    }
    @Data
    public static class OpenAIConfig {
        String secretKey = System.getenv("OPENAI_API_KEY");
        String deploymentModel = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-3.5-turbo-16k");
        Integer timeoutSec = 300;
    }


    public static ApplicationConfig load() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(ApplicationConfig.class.getResourceAsStream("/application.yml"), ApplicationConfig.class);
    }
}
