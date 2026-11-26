package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.llm.management.api.domain.LlmPrice;
import ai.mindconnect.llm.management.api.domain.LlmPriceId;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.service.LlmPriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Managing the price periods of a namespace's configs. */
@RestController
@RequestMapping("/api/ns/{namespace}/prices")
@Tag(name = "Prices", description = "Manage the price periods of the LLM configs")
class LlmPriceController {

    private final LlmPriceRepository prices;
    private final LlmPriceService service;

    LlmPriceController(LlmPriceRepository prices, LlmPriceService service) {
        this.prices = prices;
        this.service = service;
    }

    @Operation(summary = "The price periods, of one config or of all")
    @GetMapping
    List<LlmPrice> list(@PathVariable String namespace, @RequestParam(required = false) String config) {
        return config == null ? prices.findAll() : service.pricesOf(config);
    }

    @Operation(summary = "Create or replace a price period", description = "Periods of one config and model must not overlap.")
    @PostMapping
    LlmPrice save(@PathVariable String namespace, @RequestBody LlmPrice price) {
        return service.save(price);
    }

    @Operation(summary = "Delete a price period")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String namespace, @PathVariable String id) {
        service.delete(LlmPriceId.of(id));
    }
}
