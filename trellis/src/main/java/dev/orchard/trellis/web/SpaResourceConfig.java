package dev.orchard.trellis.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled orchard-ui static export (classpath:/static/) with SPA fallback (issue #78).
 * <p>
 * Registers a resource handler on /** whose resolver:
 *   - serves the real file when it exists (/, /_next/..., /favicon.ico),
 *   - falls back to index.html for extensionless client-routed paths (e.g. /groves/{uuid}),
 *     so the browser-side router can read window.location and render the route,
 *   - returns null (-> 404) for /api, /actuator, /ws prefixes and for missing dotted
 *     asset paths, so those never receive the SPA shell.
 * <p>
 * Using the resource chain (not an @Controller catch-all) is deliberate: a
 * RequestMappingHandlerMapping catch-all outranks the static ResourceHttpRequestHandler
 * and would shadow every asset. /api/** still routes to the REST controllers, which
 * outrank the resource handler.
 * <p>
 * Real-file and index.html lookups are delegated to {@code super.getResource()} so that
 * the stock {@code PathResourceResolver.checkResource} location-containment guard is
 * applied to every served resource (prevents path-traversal).
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations(STATIC_LOCATION)
            .resourceChain(true)
            .addResolver(new SpaPathResourceResolver());
    }

    /** Resolver: real file, else SPA shell for client routes, else null (404). */
    static class SpaPathResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // Excluded prefixes must never be served the SPA shell or a static file.
            if (isExcludedPrefix(resourcePath)) {
                return null;
            }
            // Real file (super performs existence + checkResource location-containment guard).
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            // Missing dotted asset -> real 404, not the SPA shell.
            if (hasExtension(resourcePath)) {
                return null;
            }
            // Client-routed path -> serve the SPA shell (also guarded by super).
            return super.getResource("index.html", location);
        }

        private boolean isExcludedPrefix(String path) {
            return path.equals("api") || path.startsWith("api/")
                || path.equals("actuator") || path.startsWith("actuator/")
                || path.equals("ws") || path.startsWith("ws/");
        }

        /**
         * Returns true if the last path segment contains a dot, indicating a file extension.
         * <p>
         * Known constraint: a client-route path segment that itself contains a dot (e.g.
         * {@code /groves/my.workspace}) would be treated as a missing asset (404) rather than
         * falling back to the SPA shell. Current routes (UUIDs, /groves, /nursery) contain
         * no dots, so this is acceptable.
         */
        private boolean hasExtension(String path) {
            int slash = path.lastIndexOf('/');
            String last = path.substring(slash + 1);
            return last.contains(".");
        }
    }
}
