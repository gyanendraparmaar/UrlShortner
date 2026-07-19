package com.gyanendra.urlshortener;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UrlShortenerController.class)
class UrlShortenerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlShortenerService service;

    @Test
    void createsAShortUrl() throws Exception {
        when(service.shorten("https://example.com/path", null)).thenReturn(
                new UrlShortenerService.ShortenResult(
                        new UrlMapping("1000000", "https://example.com/path"),
                        true));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/path"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1000000"))
                .andExpect(jsonPath("$.short_url").value("http://localhost:8080/1000000"))
                .andExpect(jsonPath("$.url").value("https://example.com/path"))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void returnsOkForAnExistingMapping() throws Exception {
        when(service.shorten("https://example.com", null)).thenReturn(
                new UrlShortenerService.ShortenResult(
                        new UrlMapping("1000000", "https://example.com"),
                        false));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void permanentlyRedirectsKnownCodes() throws Exception {
        when(service.resolve("1000000")).thenReturn("https://example.com/path");

        mockMvc.perform(get("/1000000"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com/path"));
    }

    @Test
    void returnsNotFoundForUnknownCodes() throws Exception {
        when(service.resolve("missing"))
                .thenThrow(new UrlShortenerService.UnknownCodeException("missing"));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void returnsTheExistingCodeForADuplicateAliasRequest() throws Exception {
        when(service.shorten("https://example.com", "newAlias"))
                .thenThrow(new UrlShortenerService.DuplicateUrlException("oldAlias"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","custom_alias":"newAlias"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_url"))
                .andExpect(jsonPath("$.existing_code").value("oldAlias"));
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsANonStringUrlInsteadOfCoercingIt() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":123}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void mapsValidationErrorsToBadRequest() throws Exception {
        when(service.shorten(any(), any()))
                .thenThrow(new UrlShortenerService.InvalidRequestException("url is malformed"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"not-a-url"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("url is malformed"));
    }

    @Test
    void rejectsUnknownJsonFields() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","custom_alia":"typo"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsAnInvalidConfiguredBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UrlShortenerController(service, "relative/path"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UrlShortenerController(service, "https://sho.rt/path?query=bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UrlShortenerController(service, "https://sho.rt:"));
    }
}
