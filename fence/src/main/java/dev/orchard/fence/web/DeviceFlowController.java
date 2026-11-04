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
 * The device flow's browser pages. {@link #PATH} is the {@code verification_uri}: Spring Authorization
 * Server's own verification endpoint rejects requests without a {@code user_code}, so a bare visit here
 * gets a code-entry form and {@code verification_uri_complete} ({@code ?user_code=...}) is forwarded
 * straight on. {@code /?success} is where Spring Authorization Server redirects once the device is approved.
 */
@Controller
public class DeviceFlowController {

    public static final String PATH = "/activate";

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
            <form method="get" action="%s">
            <label for="user_code">Enter the code shown in your terminal</label>
            <input id="user_code" name="user_code" autocomplete="off" autofocus required>
            <button type="submit">Continue</button>
            </form>
            </body>
            </html>
            """.formatted(VERIFICATION_ENDPOINT);

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
    ResponseEntity<String> activate(@RequestParam(name = "user_code", required = false) String userCode) {
        if (userCode == null || userCode.isBlank()) {
            return ResponseEntity.ok(FORM);
        }
        String target = UriComponentsBuilder.fromPath(VERIFICATION_ENDPOINT)
                .queryParam("user_code", userCode)
                .encode()
                .build()
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping(value = "/", params = "success", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String approved() {
        return APPROVED;
    }
}
