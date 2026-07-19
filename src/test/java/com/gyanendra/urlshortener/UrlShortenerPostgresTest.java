package com.gyanendra.urlshortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

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
}
