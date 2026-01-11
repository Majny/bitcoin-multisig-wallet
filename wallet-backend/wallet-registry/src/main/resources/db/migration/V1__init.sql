-- ===== devices =====
CREATE TABLE IF NOT EXISTS devices (
  device_id        TEXT PRIMARY KEY,
  fingerprint      TEXT NOT NULL,
  model            TEXT,
  label            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(fingerprint)
);

-- ===== wallets =====
CREATE TABLE IF NOT EXISTS wallets (
  wallet_id              TEXT PRIMARY KEY,
  network                TEXT NOT NULL,
  type                   TEXT NOT NULL,          -- SINGLE_SIG / MULTI_SIG
  script_type            TEXT NOT NULL,          -- WPKH / TR / WSH / SH_WSH ...
  m                      INT,
  n                      INT,
  account_index          INT NOT NULL DEFAULT 0,
  birth_height           INT,
  label                  TEXT,
  receive_descriptor     TEXT NOT NULL,
  change_descriptor      TEXT NOT NULL,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS wallets_type_idx ON wallets(type);

-- ===== cosigners =====
CREATE TABLE IF NOT EXISTS cosigners (
  cosigner_id     TEXT PRIMARY KEY,
  fingerprint     TEXT NOT NULL,
  origin_path     TEXT NOT NULL,
  xpub_root       TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(fingerprint, origin_path, xpub_root)
);

-- ===== wallet_cosigners =====
CREATE TABLE IF NOT EXISTS wallet_cosigners (
  wallet_id    TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  idx          INT  NOT NULL,
  cosigner_id  TEXT NOT NULL REFERENCES cosigners(cosigner_id) ON DELETE RESTRICT,
  PRIMARY KEY(wallet_id, idx),
  UNIQUE(wallet_id, cosigner_id)
);

-- ===== wallet_members =====
CREATE TABLE IF NOT EXISTS wallet_members (
  wallet_id     TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  device_id     TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  cosigner_idx  INT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(wallet_id, device_id)
);

CREATE INDEX IF NOT EXISTS wallet_members_device_idx ON wallet_members(device_id);
