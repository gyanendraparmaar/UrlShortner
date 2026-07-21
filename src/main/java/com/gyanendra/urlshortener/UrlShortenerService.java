package com.gyanendra.urlshortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UrlShortenerService {

    private static final Pattern CUSTOM_ALIAS = Pattern.compile("^[0-9A-Za-z]{3,32}$");
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_GENERATION_ATTEMPTS = 100;

    private final UrlMappingRepository repository;
    private final ShortCodeGenerator codeGenerator;

    UrlShortenerService(UrlMappingRepository repository, ShortCodeGenerator codeGenerator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
    }

    @Transactional
    ShortenResult shorten(String rawUrl, String customAlias) {
        String normalizedUrl = normalizeUrl(rawUrl);
        validateAlias(customAlias);

        Optional<UrlMapping> existing = repository.findByLongUrl(normalizedUrl);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), customAlias);
        }

        if (customAlias != null) {
            return createCustom(normalizedUrl, customAlias);
        }
        return createGenerated(normalizedUrl);
    }

    String resolve(String code) {
        return repository.findByShortCode(code)
                .map(UrlMapping::longUrl)
                .orElseThrow(() -> new UnknownCodeException(code));
    }

    private ShortenResult createGenerated(String normalizedUrl) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            Optional<UrlMapping> inserted = repository.insertGenerated(code, normalizedUrl);
            if (inserted.isPresent()) {
                return new ShortenResult(inserted.get(), true);
            }

            Optional<UrlMapping> concurrentDuplicate = repository.findByLongUrl(normalizedUrl);
            if (concurrentDuplicate.isPresent()) {
                return new ShortenResult(concurrentDuplicate.get(), false);
            }
            if (repository.findByShortCode(code).isEmpty()) {
                throw new CodeGenerationException("database rejected a unique generated mapping");
            }
            // The candidate is already in the shared generated/custom-alias namespace. Retry.
        }
        throw new CodeGenerationException("unable to allocate a unique short code after 100 attempts");
    }

    private ShortenResult createCustom(String normalizedUrl, String customAlias) {
        if (repository.findByShortCode(customAlias).isPresent()) {
            throw new AliasConflictException(customAlias);
        }

        Optional<UrlMapping> inserted = repository.insertCustom(customAlias, normalizedUrl);
        if (inserted.isPresent()) {
            return new ShortenResult(inserted.get(), true);
        }

        Optional<UrlMapping> concurrentDuplicate = repository.findByLongUrl(normalizedUrl);
        if (concurrentDuplicate.isPresent()) {
            return handleExisting(concurrentDuplicate.get(), customAlias);
        }
        throw new AliasConflictException(customAlias);
    }

    private ShortenResult handleExisting(UrlMapping existing, String customAlias) {
        if (customAlias == null || customAlias.equals(existing.shortCode())) {
            return new ShortenResult(existing, false);
        }
        throw new DuplicateUrlException(existing.shortCode());
    }

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRequestException("url must be a non-empty string");
        }
        if (rawUrl.chars().anyMatch(character ->
                Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new InvalidRequestException("url must not contain whitespace or control characters");
        }

        try {
            URI parsed = new URI(rawUrl);
            String scheme = parsed.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new InvalidRequestException("url scheme must be http or https");
            }
            if (parsed.getHost() == null || parsed.getHost().isBlank()) {
                throw new InvalidRequestException("url must include a valid host");
            }
            if (parsed.getUserInfo() != null) {
                throw new InvalidRequestException("url must not include credentials");
            }
            if (parsed.getPort() == 0
                    || parsed.getPort() > 65535
                    || parsed.getRawAuthority().endsWith(":")) {
                throw new InvalidRequestException("url contains an invalid port");
            }

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            int normalizedPort = isDefaultPort(normalizedScheme, parsed.getPort())
                    ? -1
                    : parsed.getPort();
            String normalizedUrl = rebuildWithRawComponents(
                    parsed,
                    normalizedScheme,
                    normalizedPort).toASCIIString();
            if (normalizedUrl.length() > MAX_URL_LENGTH) {
                throw new InvalidRequestException("url must be at most 2048 characters after normalization");
            }
            return normalizedUrl;
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("url is malformed");
        }
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
    }

    private static URI rebuildWithRawComponents(URI parsed, String scheme, int port)
            throws URISyntaxException {
        StringBuilder normalized = new StringBuilder()
                .append(scheme)
                .append("://")
                .append(parsed.getHost().toLowerCase(Locale.ROOT));
        if (port != -1) {
            normalized.append(':').append(port);
        }
        if (parsed.getRawPath() != null) {
            normalized.append(parsed.getRawPath());
        }
        if (parsed.getRawQuery() != null) {
            normalized.append('?').append(parsed.getRawQuery());
        }
        if (parsed.getRawFragment() != null) {
            normalized.append('#').append(parsed.getRawFragment());
        }
        return new URI(normalized.toString());
    }

    private static void validateAlias(String customAlias) {
        if (customAlias != null && !CUSTOM_ALIAS.matcher(customAlias).matches()) {
            throw new InvalidRequestException("custom_alias must be 3-32 alphanumeric characters");
        }
    }

    record ShortenResult(UrlMapping mapping, boolean created) {
    }

    static class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    static class AliasConflictException extends RuntimeException {
        AliasConflictException(String alias) {
            super("custom alias '" + alias + "' is already in use");
        }
    }

    static class DuplicateUrlException extends RuntimeException {
        private final String existingCode;

        DuplicateUrlException(String existingCode) {
            super("url is already shortened with a different code");
            this.existingCode = existingCode;
        }

        String existingCode() {
            return existingCode;
        }
    }

    static class UnknownCodeException extends RuntimeException {
        UnknownCodeException(String code) {
            super("short code '" + code + "' was not found");
        }
    }

    static class CodeGenerationException extends RuntimeException {
        CodeGenerationException(String message) {
            super(message);
        }
    }
}
