package com.gyanendra.urlshortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UrlShortenerServiceTest {

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final ShortCodeGenerator codeGenerator = mock(ShortCodeGenerator.class);
    private final UrlShortenerService service = new UrlShortenerService(repository, codeGenerator);

    @Test
    void generatesElevenBase62CharactersFromAnInjectableRandomSource() {
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(62)).thenReturn(0, 9, 10, 35, 36, 61, 1, 11, 37, 60, 2);

        String code = new ShortCodeGenerator(random).generate();

        assertEquals("09azAZ1bBY2", code);
        assertEquals(ShortCodeGenerator.CODE_LENGTH, code.length());
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
                Arguments.of("https://example.com:8443/path", "https://example.com:8443/path"),
                Arguments.of(
                        "https://Example.com/a%20b?q=a%2Fb#x%20y",
                        "https://example.com/a%20b?q=a%2Fb#x%20y"));
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
                "https://example.com:",
                "https://example.com/a path");
    }

    @Test
    void returnsTheExistingCodeForADuplicateUrl() {
        UrlMapping existing = new UrlMapping("0aA1bB2cC3d", "https://example.com");
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
    void retriesWhenAGeneratedCandidateIsAlreadyClaimed() {
        String url = "https://example.com/new";
        String firstCandidate = "0aA1bB2cC3d";
        String secondCandidate = "4eE5fF6gG7h";
        UrlMapping inserted = new UrlMapping(secondCandidate, url);
        when(repository.findByLongUrl(url)).thenReturn(Optional.empty(), Optional.empty());
        when(codeGenerator.generate()).thenReturn(firstCandidate, secondCandidate);
        when(repository.insertGenerated(firstCandidate, url)).thenReturn(Optional.empty());
        when(repository.findByShortCode(firstCandidate))
                .thenReturn(Optional.of(new UrlMapping(firstCandidate, "https://other.example")));
        when(repository.insertGenerated(secondCandidate, url)).thenReturn(Optional.of(inserted));

        UrlShortenerService.ShortenResult result = service.shorten(url, null);

        assertTrue(result.created());
        assertEquals(secondCandidate, result.mapping().shortCode());
        verify(repository).insertGenerated(firstCandidate, url);
        verify(repository).insertGenerated(secondCandidate, url);
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

    @Test
    void failsAfterTheBoundedNumberOfCandidateCollisions() {
        String url = "https://example.com/exhausted";
        when(repository.findByLongUrl(url)).thenReturn(Optional.empty());
        when(codeGenerator.generate()).thenReturn("0aA1bB2cC3d");
        when(repository.insertGenerated("0aA1bB2cC3d", url)).thenReturn(Optional.empty());
        when(repository.findByShortCode("0aA1bB2cC3d"))
                .thenReturn(Optional.of(new UrlMapping(
                        "0aA1bB2cC3d", "https://other.example")));

        UrlShortenerService.CodeGenerationException exception = assertThrows(
                UrlShortenerService.CodeGenerationException.class,
                () -> service.shorten(url, null));

        assertEquals("unable to allocate a unique short code after 100 attempts", exception.getMessage());
        verify(codeGenerator, times(100)).generate();
    }

    @Test
    void failsImmediatelyWhenTheDatabaseRejectsAUniqueMapping() {
        String url = "https://example.com/database-error";
        String candidate = "0aA1bB2cC3d";
        when(repository.findByLongUrl(url)).thenReturn(Optional.empty());
        when(codeGenerator.generate()).thenReturn(candidate);
        when(repository.insertGenerated(candidate, url)).thenReturn(Optional.empty());
        when(repository.findByShortCode(candidate)).thenReturn(Optional.empty());

        UrlShortenerService.CodeGenerationException exception = assertThrows(
                UrlShortenerService.CodeGenerationException.class,
                () -> service.shorten(url, null));

        assertEquals("database rejected a unique generated mapping", exception.getMessage());
        verify(codeGenerator).generate();
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
