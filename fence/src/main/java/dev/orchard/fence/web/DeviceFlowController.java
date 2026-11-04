package dev.orchard.fence.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * The device flow's browser pages, all under {@link #PATH}, the {@code verification_uri}. Spring Authorization
 * Server's own verification endpoint rejects requests without a {@code user_code}, so a bare visit here gets a
 * code-entry form and {@code verification_uri_complete} ({@code ?user_code=...}) is forwarded straight on. Spring
 * redirects to {@link #APPROVED_PATH} once the device is approved and to {@link #ERROR_PATH} when the code is
 * invalid or expired.
 */
@Controller
public class DeviceFlowController {

    public static final String PATH = "/activate";
    public static final String APPROVED_PATH = PATH + "/approved";
    public static final String ERROR_PATH = PATH + "?error";

    private static final String VERIFICATION_ENDPOINT = "/oauth2/device_verification";

    private static final String FORM = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Activate device</title>
            </head>
            <body>
            <h1>Activate your device</h1>
            %s<form method="get" action="%s">
            <label for="user_code">Enter the code shown in your terminal</label>
            <input id="user_code" name="user_code" autocomplete="off" autofocus required>
            <button type="submit">Continue</button>
            </form>
            </body>
            </html>
            """;

    private static final String INVALID_CODE = "<p role=\"alert\">That code is invalid or has expired. Try again.</p>\n";

    private static final String APPROVED = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Device approved</title>
            </head>
            <body>
            <h1>Device approved</h1>
            <p>You can return to your terminal.</p>
            </body>
            </html>
            """;

    @GetMapping(value = PATH, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> activate(@RequestParam(name = "user_code", required = false) String userCode,
                                    @RequestParam(name = "error", required = false) String error) {
        if (userCode == null || userCode.isBlank()) {
            return ResponseEntity.ok(FORM.formatted(error == null ? "" : INVALID_CODE, VERIFICATION_ENDPOINT));
        }
        String target = UriComponentsBuilder.fromPath(VERIFICATION_ENDPOINT)
                .queryParam("user_code", userCode)
                .encode()
                .build()
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping(value = APPROVED_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String approved() {
        return APPROVED;
    }
}
