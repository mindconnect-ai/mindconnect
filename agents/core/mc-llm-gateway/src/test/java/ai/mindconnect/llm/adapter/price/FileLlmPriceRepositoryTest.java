package ai.mindconnect.llm.adapter.price;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.llm.adapter.file.FileLlmPriceRepository;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepositoryContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    @TempDir
    Path dir;

    @Override
    protected LlmPriceRepository repository() {
        return new FileLlmPriceRepository(dir, new Namespace("test"));
    }

    @Test
    void aPeriodIsAFileUnderTheNamespacesSystemDirWithDatesAsText() throws Exception {
        LlmPrice price = price("claude", "2026-01-01", "2026-07-01", "3", "15", null);
        repository().save(price);

        Path file = dir.resolve("test/system/llm-prices/" + price.id().value() + ".json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"validFrom\":\"2026-01-01\"").contains("\"validTo\":\"2026-07-01\"")
                .contains("\"model\":\"" + MODEL + "\"");
        assertThat(new FileLlmPriceRepository(dir, new Namespace("other")).findAll()).isEmpty();
    }

    @Test
    void aFileWrittenBeforePricesNamedTheirModelStillReadsAndPricesAnyModel() throws Exception {
        Path file = dir.resolve("test/system/llm-prices/old-price.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"id\":\"old-price\",\"configName\":\"claude\",\"validFrom\":\"2026-01-01\","
                + "\"currency\":\"USD\",\"inputPerMillion\":3,\"outputPerMillion\":15}");

        LlmPriceRepository repo = repository();

        assertThat(repo.findAll()).singleElement().satisfies(p -> assertThat(p.anyModel()).isTrue());
        assertThat(repo.priceAt("claude", "whatever-model", Instant.parse("2026-09-24T00:00:00Z"))).isPresent();
    }
}
