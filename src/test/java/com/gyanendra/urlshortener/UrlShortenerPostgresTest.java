package com.gyanendra.urlshortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UrlShortenerPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.10-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UrlShortenerService service;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE url_mappings RESTART IDENTITY");
    }

    @Test
    void shortensPersistsAndRedirects() throws Exception {
        MvcResult shortened = mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/articles/42"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1000000"))
                .andReturn();

        JsonNode response = objectMapper.readTree(shortened.getResponse().getContentAsString());
        String code = response.get("code").asText();

        mockMvc.perform(get("/" + code))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com/articles/42"));
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM url_mappings", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);
    }

    @Test
    void normalizesDuplicatesAndReturnsTheOriginalCode() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"HTTPS://Example.COM:443/path"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1000000"));

        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/path"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1000000"))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void enforcesCustomAliasSemantics() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/first","custom_alias":"myAlias"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("myAlias"));

        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/first","custom_alias":"myAlias"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("myAlias"))
                .andExpect(jsonPath("$.created").value(false));

        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/other","custom_alias":"myAlias"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("alias_conflict"));

        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/first","custom_alias":"otherAlias"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_url"))
                .andExpect(jsonPath("$.existing_code").value("myAlias"));
    }

    @Test
    void advancesPastAGeneratedCodeClaimedByACustomAlias() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/custom","custom_alias":"1000001"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/generated"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1000002"));
    }

    @Test
    void returnsNotFoundWithoutRedirectingAnUnknownCode() throws Exception {
        mockMvc.perform(get("/doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void supportsAnAliasThatMatchesSpringsDefaultErrorPath() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://example.com/error-page","custom_alias":"error"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/error"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com/error-page"));
    }

    @Test
    void handlesConcurrentDuplicateRequestsWithoutCreatingCollisions() {
        int requestCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<CompletableFuture<UrlShortenerService.ShortenResult>> requests =
                    java.util.stream.IntStream.range(0, requestCount)
                            .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                                await(start);
                                return service.shorten("https://example.com/concurrent", null);
                            }, executor))
                            .toList();
            start.countDown();

            List<UrlShortenerService.ShortenResult> results = requests.stream()
                    .map(CompletableFuture::join)
                    .toList();
            Set<String> codes = results.stream()
                    .map(result -> result.mapping().shortCode())
                    .collect(java.util.stream.Collectors.toSet());

            org.assertj.core.api.Assertions.assertThat(codes).hasSize(1);
            org.assertj.core.api.Assertions.assertThat(results.stream()
                    .filter(UrlShortenerService.ShortenResult::created)
                    .count()).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM url_mappings",
                    Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resolvesAMappingAfterTheApplicationContextRestarts() {
        String code;
        try (ConfigurableApplicationContext first = newApplicationContext()) {
            code = first.getBean(UrlShortenerService.class)
                    .shorten("https://example.com/persistent", null)
                    .mapping()
                    .shortCode();
        }

        try (ConfigurableApplicationContext second = newApplicationContext()) {
            org.assertj.core.api.Assertions.assertThat(
                    second.getBean(UrlShortenerService.class).resolve(code))
                    .isEqualTo("https://example.com/persistent");
        }
    }

    private static ConfigurableApplicationContext newApplicationContext() {
        return new SpringApplicationBuilder(UrlShortenerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--app.base-url=http://localhost:8080",
                        "--spring.main.banner-mode=off");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while synchronizing test requests", exception);
        }
    }
}
