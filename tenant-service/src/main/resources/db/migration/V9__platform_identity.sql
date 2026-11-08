-- v10 platform identity (docs/20-v10-enterprise-sso-platform-identity.md section 2).
-- H2 / PostgreSQL compatible ANSI subset.

CREATE TABLE platform_users (
  id            VARCHAR(36) PRIMARY KEY,
  username      VARCHAR(64) NOT NULL UNIQUE,
  display_name  VARCHAR(128),
  password_hash VARCHAR(256) NOT NULL,
  roles         VARCHAR(256) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  email         VARCHAR(128),
  created_at    TIMESTAMP NOT NULL,
  updated_at    TIMESTAMP NOT NULL,
  last_login_at TIMESTAMP
);

CREATE TABLE sso_providers (
  id                VARCHAR(36) PRIMARY KEY,
  code              VARCHAR(32) NOT NULL UNIQUE,
  name              VARCHAR(64) NOT NULL,
  issuer            VARCHAR(512),
  client_id         VARCHAR(256),
  client_secret     VARCHAR(512),
  scopes            VARCHAR(256),
  redirect_uri      VARCHAR(512),
  frontend_redirect VARCHAR(512),
  role_claim        VARCHAR(64),
  default_role      VARCHAR(32),
  enabled           BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL
);

CREATE INDEX idx_sso_providers_enabled ON sso_providers(enabled);
