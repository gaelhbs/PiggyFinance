ALTER TABLE users
    ADD COLUMN phone_number VARCHAR(20) UNIQUE;

CREATE TABLE whatsapp_link_codes (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id),
    code       VARCHAR(10) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_whatsapp_link_codes_user_id ON whatsapp_link_codes(user_id);
