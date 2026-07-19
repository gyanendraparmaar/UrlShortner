package com.gyanendra.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UrlShortenerServiceTest {

    private static final long SEVEN_CHARACTER_START = 56_800_235_584L;

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlShortenerService service = new UrlShortenerService(repository);

    @Test
    void encodesBase62UsingTheAlexXuAlphabet() {
        assertEquals("0", UrlShortenerService.encodeBase62(0));
        assertEquals("Z", UrlShortenerService.encodeBase62(61));
        assertEquals("10", UrlShortenerService.encodeBase62(62));
        assertEquals("2TX", UrlShortenerService.encodeBase62(11_157));
        assertEquals("1000000", UrlShortenerService.encodeBase62(SEVEN_CHARACTER_START));
    }

    @Test
    void rejectsNegativeBase62Values() {
        assertThrows(IllegalArgumentException.class, () -> UrlShortenerService.encodeBase62(-1));
    }

    @ParameterizedTest
    @MethodSource("validUrls")
    void normalizesUrlsForDuplicateDetection(String input, String expected) {
        assertEquals(expected, UrlShortenerService.normalizeUrl(input));
    }

    static Stream<Arguments> validUrls() {
        return Stream.of(
                Arguments.of("HTTPS://Example.COM:443/a?q=1#part", "https://example.com/a?q=1#part"),
                Arguments.of("http://Example.com:80", "http://example.com"),
                Arguments.of("https://example.com:8443/path", "https://example.com:8443/path"));
    }

    @ParameterizedTest
    @MethodSource("invalidUrls")
    void rejectsInvalidUrls(String input) {
        assertThrows(
                UrlShortenerService.InvalidRequestException.class,
                () -> UrlShortenerService.normalizeUrl(input));
    }

    static Stream<String> invalidUrls() {
        return Stream.of(
                "",
                "example.com/path",
                "ftp://example.com/file",
                "https:///missing-host",
                "https://user:secret@example.com",
                "https://example.com:0",
                "https://example.com:99999",
                "https://example.com/a path");
    }

    @Test
    void returnsTheExistingCodeForADuplicateUrl() {
        UrlMapping existing = new UrlMapping("1000000", "https://example.com");
        when(repository.findByLongUrl("https://example.com")).thenReturn(Optional.of(existing));

        UrlShortenerService.ShortenResult result = service.shorten("HTTPS://EXAMPLE.COM:443", null);

        assertEquals(existing, result.mapping());
        assertFalse(result.created());
    }

    @Test
    void rejectsADifferentAliasForAnExistingUrl() {
        UrlMapping existing = new UrlMapping("firstAlias", "https://example.com");
        when(repository.findByLongUrl("https://example.com")).thenReturn(Optional.of(existing));

        UrlShortenerService.DuplicateUrlException error = assertThrows(
                UrlShortenerService.DuplicateUrlException.class,
                () -> service.shorten("https://example.com", "secondAlias"));

        assertEquals("firstAlias", error.existingCode());
    }

    @Test
    void skipsACodeAlreadyClaimedByACustomAlias() {
        String url = "https://example.com/new";
        UrlMapping inserted = new UrlMapping("1000001", url);
        when(repository.findByLongUrl(url)).thenReturn(Optional.empty(), Optional.empty());
        when(repository.nextId()).thenReturn(SEVEN_CHARACTER_START, SEVEN_CHARACTER_START + 1);
        when(repository.insertGenerated(SEVEN_CHARACTER_START, "1000000", url))
                .thenReturn(Optional.empty());
        when(repository.insertGenerated(SEVEN_CHARACTER_START + 1, "1000001", url))
                .thenReturn(Optional.of(inserted));

        UrlShortenerService.ShortenResult result = service.shorten(url, null);

        assertTrue(result.created());
        assertEquals("1000001", result.mapping().shortCode());
        verify(repository).insertGenerated(SEVEN_CHARACTER_START, "1000000", url);
        verify(repository).insertGenerated(SEVEN_CHARACTER_START + 1, "1000001", url);
    }

    @Test
    void rejectsAnAliasAlreadyUsedByAnotherUrl() {
        String url = "https://example.com/new";
        when(repository.findByLongUrl(url)).thenReturn(Optional.empty());
        when(repository.findByShortCode("myAlias"))
                .thenReturn(Optional.of(new UrlMapping("myAlias", "https://other.example")));

        assertThrows(
                UrlShortenerService.AliasConflictException.class,
                () -> service.shorten(url, "myAlias"));
    }

    @ParameterizedTest
    @MethodSource("invalidAliases")
    void rejectsInvalidAliases(String alias) {
        assertThrows(
                UrlShortenerService.InvalidRequestException.class,
                () -> service.shorten("https://example.com", alias));
    }

    static Stream<String> invalidAliases() {
        return Stream.of("", "ab", "contains-dash", "contains space", "a".repeat(33));
    }
}

