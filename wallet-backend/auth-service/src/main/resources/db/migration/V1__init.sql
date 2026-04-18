CREATE TABLE IF NOT EXISTS devices (
  device_id        TEXT PRIMARY KEY,
  fingerprint      TEXT NOT NULL,
  model            TEXT,
  label            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(fingerprint)
);

-- Refresh tokens. Plaintext token is returned to client once at issue time;
-- only the SHA-256 hash is stored so a DB read does not expose live tokens.
-- revoked_at is set on rotation (single-use) so reuse attempts get rejected.
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash       TEXT PRIMARY KEY,
  device_id        TEXT NOT NULL,
  fingerprint      TEXT,
  expires_at       TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device ON refresh_tokens(device_id);
