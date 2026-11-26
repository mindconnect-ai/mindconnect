package ai.mindconnect.llm.gateway.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mindconnect.llm.store=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class LlmGatewayApplicationTest {

    private static final String CONFIG = """
            {"name":"chat","provider":"OPENAI","model":"gpt-5.4-mini",
             "baseUrl":"https://api.openai.com","apiKey":"${OPENAI_API_KEY}"}""";

    @Autowired
    private MockMvc mvc;

    @Test
    void a_config_saved_without_an_id_is_found_by_its_name_in_its_namespace_only() throws Exception {
        mvc.perform(post("/api/ns/alpha/configs").contentType(MediaType.APPLICATION_JSON).content(CONFIG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty());

        mvc.perform(get("/api/ns/alpha/configs/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-5.4-mini"));
        mvc.perform(get("/api/ns/beta/configs/chat")).andExpect(status().isNotFound());
    }

    @Test
    void saving_a_config_again_by_name_replaces_it() throws Exception {
        mvc.perform(post("/api/ns/gamma/configs").contentType(MediaType.APPLICATION_JSON).content(CONFIG));
        mvc.perform(post("/api/ns/gamma/configs").contentType(MediaType.APPLICATION_JSON)
                .content(CONFIG.replace("gpt-5.4-mini", "gpt-5.4")));

        mvc.perform(get("/api/ns/gamma/configs"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].model").value("gpt-5.4"));
    }

    @Test
    void a_deleted_config_takes_its_prices_with_it() throws Exception {
        mvc.perform(post("/api/ns/delta/configs").contentType(MediaType.APPLICATION_JSON).content(CONFIG));
        mvc.perform(post("/api/ns/delta/prices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"id":"p1","configName":"chat","model":"gpt-5.4-mini","validFrom":"2026-01-01",
                         "currency":"USD","inputPerMillion":0.25,"outputPerMillion":2}"""))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/ns/delta/configs/chat")).andExpect(status().isNoContent());

        mvc.perform(get("/api/ns/delta/prices")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void a_price_for_an_unknown_config_is_a_bad_request() throws Exception {
        mvc.perform(post("/api/ns/epsilon/prices").contentType(MediaType.APPLICATION_JSON).content("""
                        {"id":"p1","configName":"nope","model":"m","validFrom":"2026-01-01",
                         "currency":"USD","inputPerMillion":1,"outputPerMillion":1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_chat_with_an_unknown_config_is_not_found() throws Exception {
        mvc.perform(post("/api/ns/zeta/chat").contentType(MediaType.APPLICATION_JSON).content("""
                        {"configName":"nope","messages":[{"role":"USER","content":"hi"}]}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_default_namespace_starts_with_the_openai_configs() throws Exception {
        mvc.perform(get("/api/ns/default/configs"))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "openai-chat", "openai-embedding", "openai-transcription")))
                .andExpect(jsonPath("$[?(@.name=='openai-embedding')].type").value("EMBEDDING"))
                .andExpect(jsonPath("$[?(@.name=='openai-transcription')].type").value("SPEECH_TO_TEXT"));
    }

    @Test
    void the_swagger_description_lists_chat_and_management() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/ns/{namespace}/chat']").exists())
                .andExpect(jsonPath("$.paths['/api/ns/{namespace}/configs']").exists())
                .andExpect(jsonPath("$.paths['/api/ns/{namespace}/prices']").exists());
    }
}
