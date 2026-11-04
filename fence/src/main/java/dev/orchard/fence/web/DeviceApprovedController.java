package dev.orchard.fence.web;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Landing page for {@code /?success}, where Spring Authorization Server redirects after a device is approved. */
@Controller
public class DeviceApprovedController {

    private static final String PAGE = """
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

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String approved() {
        return PAGE;
    }
}
