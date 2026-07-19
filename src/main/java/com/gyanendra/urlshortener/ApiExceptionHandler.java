package com.gyanendra.urlshortener;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(UrlShortenerService.InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(UrlShortenerService.InvalidRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableRequest() {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "request body must be valid JSON", null);
    }

    @ExceptionHandler(UrlShortenerService.AliasConflictException.class)
    ResponseEntity<ApiError> aliasConflict(UrlShortenerService.AliasConflictException exception) {
        return error(HttpStatus.CONFLICT, "alias_conflict", exception.getMessage(), null);
    }

    @ExceptionHandler(UrlShortenerService.DuplicateUrlException.class)
    ResponseEntity<ApiError> duplicateUrl(UrlShortenerService.DuplicateUrlException exception) {
        return error(
                HttpStatus.CONFLICT,
                "duplicate_url",
                exception.getMessage(),
                exception.existingCode());
    }

    @ExceptionHandler(UrlShortenerService.UnknownCodeException.class)
    ResponseEntity<ApiError> unknownCode(UrlShortenerService.UnknownCodeException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found", exception.getMessage(), null);
    }

    @ExceptionHandler(UrlShortenerService.CodeGenerationException.class)
    ResponseEntity<ApiError> codeGeneration(UrlShortenerService.CodeGenerationException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "code_generation_unavailable", exception.getMessage(), null);
    }

    private static ResponseEntity<ApiError> error(
            HttpStatus status,
            String error,
            String message,
            String existingCode) {
        return ResponseEntity.status(status).body(new ApiError(error, message, existingCode));
    }

    record ApiError(String error, String message, String existingCode) {
    }
}
