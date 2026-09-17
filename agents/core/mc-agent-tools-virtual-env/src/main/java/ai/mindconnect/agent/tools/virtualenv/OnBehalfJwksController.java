package ai.mindconnect.agent.tools.virtualenv;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public key of the on-behalf tokens. Public on purpose: it holds
 * nothing secret, and the virtual environment server must reach it without a
 * token of its own.
 */
@RestController
public class OnBehalfJwksController {

    /** Where the keys are served; the virtual environment server's {@code jwk-set-uri}. */
    public static final String PATH = "/.well-known/mc-virtual-env/jwks.json";

    private final OnBehalfTokens tokens;

    public OnBehalfJwksController(OnBehalfTokens tokens) {
        this.tokens = tokens;
    }

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return tokens.jwks();
    }
}
