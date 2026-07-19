package com.gyanendra.urlshortener;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class UrlMappingRepository {

    private static final String RETURNING_COLUMNS = " RETURNING short_code, long_url";

    private final JdbcTemplate jdbcTemplate;

    UrlMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<UrlMapping> findByLongUrl(String longUrl) {
        return first(jdbcTemplate.query(
                "SELECT short_code, long_url FROM url_mappings WHERE long_url = ?",
                (resultSet, rowNumber) -> new UrlMapping(
                        resultSet.getString("short_code"),
                        resultSet.getString("long_url")),
                longUrl));
    }

    Optional<UrlMapping> findByShortCode(String shortCode) {
        return first(jdbcTemplate.query(
                "SELECT short_code, long_url FROM url_mappings WHERE short_code = ?",
                (resultSet, rowNumber) -> new UrlMapping(
                        resultSet.getString("short_code"),
                        resultSet.getString("long_url")),
                shortCode));
    }

    long nextId() {
        Long id = jdbcTemplate.queryForObject("SELECT nextval('short_code_sequence')", Long.class);
        if (id == null) {
            throw new IllegalStateException("The short-code sequence returned no value");
        }
        return id;
    }

    Optional<UrlMapping> insertGenerated(long id, String shortCode, String longUrl) {
        return insert(
                "INSERT INTO url_mappings (id, short_code, long_url) VALUES (?, ?, ?) "
                        + "ON CONFLICT DO NOTHING" + RETURNING_COLUMNS,
                id, shortCode, longUrl);
    }

    Optional<UrlMapping> insertCustom(String shortCode, String longUrl) {
        return insert(
                "INSERT INTO url_mappings (short_code, long_url) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING" + RETURNING_COLUMNS,
                shortCode, longUrl);
    }

    private Optional<UrlMapping> insert(String sql, Object... arguments) {
        return first(jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new UrlMapping(
                        resultSet.getString("short_code"),
                        resultSet.getString("long_url")),
                arguments));
    }

    private static Optional<UrlMapping> first(List<UrlMapping> mappings) {
        return mappings.stream().findFirst();
    }
}

