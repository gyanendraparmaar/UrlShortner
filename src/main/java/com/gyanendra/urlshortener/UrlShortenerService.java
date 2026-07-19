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

    private static final char[] BASE62_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final Pattern CUSTOM_ALIAS = Pattern.compile("^[0-9A-Za-z]{3,32}$");
    private static final int MAX_URL_LENGTH = 2048;

    private final UrlMappingRepository repository;

    UrlShortenerService(UrlMappingRepository repository) {
        this.repository = repository;
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
        while (true) {
            long id = repository.nextId();
            String code = encodeBase62(id);
            Optional<UrlMapping> inserted = repository.insertGenerated(id, code, normalizedUrl);
            if (inserted.isPresent()) {
                return new ShortenResult(inserted.get(), true);
            }

            Optional<UrlMapping> concurrentDuplicate = repository.findByLongUrl(normalizedUrl);
            if (concurrentDuplicate.isPresent()) {
                return new ShortenResult(concurrentDuplicate.get(), false);
            }
            // A custom alias claimed this Base62 value. The next sequence ID is still unique.
        }
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
            if (parsed.getPort() == 0 || parsed.getPort() > 65535) {
                throw new InvalidRequestException("url contains an invalid port");
            }

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            int normalizedPort = isDefaultPort(normalizedScheme, parsed.getPort())
                    ? -1
                    : parsed.getPort();
            URI normalized = new URI(
                    normalizedScheme,
                    null,
                    parsed.getHost().toLowerCase(Locale.ROOT),
                    normalizedPort,
                    parsed.getRawPath(),
                    parsed.getRawQuery(),
                    parsed.getRawFragment());
            String normalizedUrl = normalized.toASCIIString();
            if (normalizedUrl.length() > MAX_URL_LENGTH) {
                throw new InvalidRequestException("url must be at most 2048 characters after normalization");
            }
            return normalizedUrl;
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("url is malformed");
        }
    }

    static String encodeBase62(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 value must not be negative");
        }
        if (value == 0) {
            return String.valueOf(BASE62_ALPHABET[0]);
        }

        StringBuilder encoded = new StringBuilder();
        while (value > 0) {
            encoded.append(BASE62_ALPHABET[(int) (value % BASE62_ALPHABET.length)]);
            value /= BASE62_ALPHABET.length;
        }
        return encoded.reverse().toString();
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
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
}

