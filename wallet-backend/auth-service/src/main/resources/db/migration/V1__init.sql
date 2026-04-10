CREATE TABLE IF NOT EXISTS devices (
  device_id        TEXT PRIMARY KEY,
  fingerprint      TEXT NOT NULL,
  model            TEXT,
  label            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(fingerprint)
);
