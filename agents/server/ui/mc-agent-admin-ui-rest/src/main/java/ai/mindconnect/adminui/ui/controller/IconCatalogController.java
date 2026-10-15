package ai.mindconnect.adminui.ui.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of every icon the UI can draw, for the icon picker.
 *
 * <p>The icons themselves are one SVG sprite that ships inside
 * mc-semantic-ui-core ({@code /sui/icons.svg}, the Lucide set). Drawing one is
 * a {@code <use href="/sui/icons.svg#name">} — but a picker needs the list of
 * names, and the sprite is two thirds of a megabyte. So the ids are read out of
 * it once, here, and served as a few kilobytes of JSON.
 *
 * <p>Read once and cached for the life of the process: the sprite is a build
 * artifact inside a jar and cannot change while the server runs.
 */
@RestController
@RequestMapping("/admin/api/icons")
public class IconCatalogController {

    /** The sprite as it sits in the mc-semantic-ui-core jar. */
    private static final String SPRITE = "META-INF/resources/sui/icons.svg";

    private static final Pattern SYMBOL_ID = Pattern.compile("<symbol\\s+id=\"([^\"]+)\"");

    private volatile List<String> names;

    @GetMapping
    public ResponseEntity<List<String>> list() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                // Immutable for this process; let the browser keep it too.
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                .body(names());
    }

    private List<String> names() throws IOException {
        List<String> cached = names;
        if (cached != null) return cached;
        synchronized (this) {
            if (names == null) names = readSprite();
            return names;
        }
    }

    /**
     * Streams the sprite line by line rather than reading it whole: every
     * symbol sits on its own line, and the file is large enough that holding
     * it in memory to throw all but the ids away would be careless.
     */
    private static List<String> readSprite() throws IOException {
        var resource = new ClassPathResource(SPRITE);
        if (!resource.exists()) return List.of();
        List<String> ids = new ArrayList<>();
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = SYMBOL_ID.matcher(line);
                while (m.find()) ids.add(m.group(1));
            }
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }
}
