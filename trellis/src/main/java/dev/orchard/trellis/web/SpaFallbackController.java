package dev.orchard.trellis.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA fallback for the bundled orchard-ui (issue #78).
 * <p>
 * Forwards client-routed paths (no file extension, not an API/infra prefix) to the
 * static SPA shell at /index.html. The browser-side router then reads window.location
 * and renders the correct route. Paths containing a dot are treated as assets and are
 * left alone, so a missing asset yields a real 404 instead of the SPA shell.
 */
@Controller
public class SpaFallbackController {

    private static final java.util.regex.Pattern EXCLUDED =
        java.util.regex.Pattern.compile("^/(api|actuator|ws)(/.*)?$");

    /**
     * Catch-all handler: forwards extensionless non-API paths to the SPA shell.
     * API/actuator/ws prefixes and paths with a file extension bypass the forward
     * and receive a real 404 so clients get accurate error responses.
     */
    @RequestMapping("/{*path}")
    public String forward(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        // Strip query string if present (getRequestURI does not include it, but be safe)
        String lastSegment = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
        if (EXCLUDED.matcher(uri).matches() || lastSegment.contains(".")) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return null; // no view — response already committed with 404
        }
        return "forward:/index.html";
    }
}
