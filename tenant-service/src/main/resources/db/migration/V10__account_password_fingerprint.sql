-- v10.1 multi-tenant membership: deterministic password fingerprint so the same plaintext
-- password on two tenant accounts can be recognised across different PBKDF2 salts
-- (docs/20 section 3). The value is only used for equality checks, never for authentication.

ALTER TABLE accounts ADD COLUMN password_fingerprint VARCHAR(64);
