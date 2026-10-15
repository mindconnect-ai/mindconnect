package ai.mindconnect.adminui.branding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves {@code mindconnect.branding.assets-dir} at {@code /branding/**}, so a
 * logo and a stylesheet can be dropped next to a deployed app instead of built
 * into it. Nothing is registered when the setting is unset — then only URLs
 * into the app's own {@code static/} and absolute ones work.
 */
@Configuration
@Slf4j
public class BrandingAssetsConfig implements WebMvcConfigurer {

    private final BrandingProperties branding;

    public BrandingAssetsConfig(BrandingProperties branding) {
        this.branding = branding;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String configured = branding.getAssetsDir();
        if (configured == null) {
            return;
        }
        Path dir = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            // Not fatal: the directory may be mounted later. Saying so beats a
            // 404 on a logo whose path looks right.
            log.warn("mindconnect.branding.assets-dir is {}, which is not a directory — {}/** serves nothing yet",
                    dir, BrandingProperties.ASSETS_PATH);
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(BrandingProperties.ASSETS_PATH + "/**").addResourceLocations(location);
        log.info("Branding assets: {}/** -> {}", BrandingProperties.ASSETS_PATH, dir);
    }
}
