package com.gyanendra.urlshortener;

import java.net.URI;

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
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    @PostMapping(path = "/shorten", consumes = "application/json", produces = "application/json")
    ResponseEntity<ShortenResponse> shorten(@RequestBody ShortenRequest request) {
        if (request == null) {
            throw new UrlShortenerService.InvalidRequestException("request body is required");
        }

        UrlShortenerService.ShortenResult result =
                service.shorten(request.url(), request.customAlias());
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

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    record ShortenRequest(String url, String customAlias) {
    }

    record ShortenResponse(String code, String shortUrl, String url, boolean created) {
    }
}

