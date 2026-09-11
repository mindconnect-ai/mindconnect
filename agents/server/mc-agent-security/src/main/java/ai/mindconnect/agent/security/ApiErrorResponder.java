package ai.mindconnect.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the API answers a request it does not let through: the status and the
 * {@code WWW-Authenticate} header a bearer client expects, plus a short JSON
 * body a person reading a bare {@code curl} can act on —
 * {@code {"status":401,"error":"Unauthorized","message":"…"}}.
 *
 * <p>Without a token the message says what kind of token to send; with a
 * rejected one it repeats why it was rejected, the same text the
 * {@code WWW-Authenticate} header carries.
 */
public class ApiErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String TOKEN_REQUIRED =
            "A bearer token is required: a personal API token (mct_...) or an access token of the identity provider";
    static final String ACCESS_DENIED = "Access denied";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BearerTokenAuthenticationEntryPoint unauthorized = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler forbidden = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        // Sets the status (401, or 400 for a malformed Authorization header) and the header.
        unauthorized.commence(request, response, exception);
        write(response, exception instanceof OAuth2AuthenticationException oauth
                && oauth.getError().getDescription() != null
                ? oauth.getError().getDescription()
                : TOKEN_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        forbidden.handle(request, response, exception);
        write(response, ACCESS_DENIED);
    }

    private static void write(HttpServletResponse response, String message) throws IOException {
        HttpStatus status = HttpStatus.resolve(response.getStatus());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", response.getStatus());
        body.put("error", status == null ? "" : status.getReasonPhrase());
        body.put("message", message);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(body));
    }
}
