package com.gyanendra.urlshortener;

import java.net.URI;
import java.net.URISyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UrlShortenerController {

    private final UrlShortenerService service;
    private final String baseUrl;

    UrlShortenerController(
            UrlShortenerService service,
            @Value("${app.base-url}") String baseUrl) {
        this.service = service;
        this.baseUrl = validateBaseUrl(baseUrl);
    }

    @PostMapping(path = "/shorten", consumes = "application/json", produces = "application/json")
    ResponseEntity<ShortenResponse> shorten(@RequestBody ShortenRequest request) {
        if (request == null) {
            throw new UrlShortenerService.InvalidRequestException("request body is required");
        }

        String url = requiredText(request.url(), "url");
        String customAlias = optionalText(request.customAlias(), "custom_alias");
        UrlShortenerService.ShortenResult result =
                service.shorten(url, customAlias);
        UrlMapping mapping = result.mapping();
        ShortenResponse response = new ShortenResponse(
                mapping.shortCode(),
                baseUrl + "/" + mapping.shortCode(),
                mapping.longUrl(),
                result.created());
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    @GetMapping("/{code}")
    ResponseEntity<Void> redirect(@PathVariable String code) {
        return ResponseEntity
                .status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(service.resolve(code)))
                .build();
    }

    private static String validateBaseUrl(String value) {
        try {
            URI parsed = new URI(value);
            String scheme = parsed.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null
                    || parsed.getPort() == 0
                    || parsed.getPort() > 65535
                    || parsed.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("app.base-url must be an absolute HTTP(S) URL");
            }

            String normalized = parsed.toASCIIString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (NullPointerException | URISyntaxException exception) {
            throw new IllegalArgumentException("app.base-url must be an absolute HTTP(S) URL", exception);
        }
    }

    private static String requiredText(JsonNode value, String field) {
        if (value == null || !value.isTextual()) {
            throw new UrlShortenerService.InvalidRequestException(
                    field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new UrlShortenerService.InvalidRequestException(field + " must be a string");
        }
        return value.textValue();
    }

    record ShortenRequest(JsonNode url, JsonNode customAlias) {
    }

    record ShortenResponse(String code, String shortUrl, String url, boolean created) {
    }
}
