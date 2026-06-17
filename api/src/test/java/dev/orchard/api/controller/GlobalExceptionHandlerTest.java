package dev.orchard.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void noResourceFoundReturns404() {
        var handler = new GlobalExceptionHandler();
        var ex = new NoResourceFoundException(HttpMethod.GET, "/_next/missing.js", "/_next/missing.js");
        var response = handler.handleNoResource(ex);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().get("status"));
    }
}
