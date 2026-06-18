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
 *   - serves the real file when it exists for extension-bearing paths (/_next/..., /favicon.ico),
 *   - serves a prerendered per-route {@code <route>/index.html} when Next.js emitted one
 *     (e.g. {@code groves/index.html} for the {@code /groves} route),
 *   - serves the dynamic-route placeholder shell {@code <parent>/_/index.html} for a deep
 *     client route with no exact page (e.g. {@code groves/_/index.html} for /groves/{uuid}),
 *   - falls back to the root {@code index.html} SPA shell for any remaining extensionless route,
 *   - returns null (-> 404) for /api, /actuator, /ws prefixes and for missing dotted
 *     asset paths, so those never receive the SPA shell.
 * <p>
 * Extensionless paths are NEVER served as raw directory resources. In the GraalVM native image,
 * classpath directories report {@code isReadable()==true}; Spring would serve them as
 * {@code application/octet-stream} (a directory listing). The resolver avoids this by routing
 * all extensionless paths through the prerendered-index or SPA-shell lookup, never returning
 * a raw directory.
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

    /**
     * Directory name Next.js static export uses for a dynamic route segment's prerendered
     * placeholder shell (this app's {@code generateStaticParams} emits a single {@code _}
     * param), e.g. {@code groves/_/index.html} for {@code /groves/[id]}.
     */
    private static final String DYNAMIC_ROUTE_PLACEHOLDER = "_";

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
            // Asset request (file extension in the last segment): serve the real file via
            // super (existence + checkResource location guard), or null -> 404 if missing.
            // Never falls back to the SPA shell.
            if (hasExtension(resourcePath)) {
                return super.getResource(resourcePath, location);
            }
            // Extensionless path = a client route, possibly a directory like "groves/".
            // Serve the prerendered per-route index.html when Next emitted one, otherwise
            // the SPA shell. Never return a raw directory resource: the native image
            // reports classpath directories as readable, and Spring serves them as
            // application/octet-stream, which browsers download.
            String route = stripTrailingSlashes(resourcePath);
            if (!route.isEmpty()) {
                Resource routeIndex = super.getResource(route + "/index.html", location);
                if (routeIndex != null) {
                    return routeIndex;
                }
                // Next.js static-export dynamic route: /groves/<id> has no exact prerendered
                // page, but the export emits a placeholder shell at the dynamic segment
                // (generateStaticParams -> groves/_/index.html). Try the parent route with the
                // last segment replaced by the "_" placeholder before falling back to the root
                // shell — the root index.html is an error shell, so serving it breaks deep links.
                int lastSlash = route.lastIndexOf('/');
                if (lastSlash >= 0) {
                    String placeholderIndex =
                        route.substring(0, lastSlash) + "/" + DYNAMIC_ROUTE_PLACEHOLDER + "/index.html";
                    Resource dynamicIndex = super.getResource(placeholderIndex, location);
                    if (dynamicIndex != null) {
                        return dynamicIndex;
                    }
                }
            }
            return super.getResource("index.html", location);
        }

        private String stripTrailingSlashes(String path) {
            int end = path.length();
            while (end > 0 && path.charAt(end - 1) == '/') {
                end--;
            }
            return path.substring(0, end);
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
