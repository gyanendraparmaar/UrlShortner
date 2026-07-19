CREATE SEQUENCE short_code_sequence
    AS BIGINT
    START WITH 56800235584;

CREATE TABLE url_mappings (
    id BIGINT PRIMARY KEY DEFAULT nextval('short_code_sequence'),
    short_code VARCHAR(32) NOT NULL UNIQUE,
    long_url VARCHAR(2048) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT short_code_is_alphanumeric CHECK (short_code ~ '^[0-9A-Za-z]+$')
);

ALTER SEQUENCE short_code_sequence OWNED BY url_mappings.id;
