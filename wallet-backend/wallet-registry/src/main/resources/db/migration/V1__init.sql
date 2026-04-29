-- wallets
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

-- cosigners
CREATE TABLE IF NOT EXISTS cosigners (
  cosigner_id     TEXT PRIMARY KEY,
  fingerprint     TEXT NOT NULL,
  origin_path     TEXT NOT NULL,
  xpub_root       TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(fingerprint, origin_path, xpub_root)
);

-- wallet_cosigners
CREATE TABLE IF NOT EXISTS wallet_cosigners (
  wallet_id    TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  idx          INT  NOT NULL,
  cosigner_id  TEXT NOT NULL REFERENCES cosigners(cosigner_id) ON DELETE RESTRICT,
  PRIMARY KEY(wallet_id, idx),
  UNIQUE(wallet_id, cosigner_id)
);

-- cosigner_labels
-- Per-device labels for cosigner positions. Each Trezor (device_id from JWT,
-- deterministic UUID derived from master fingerprint) has its own labels so they
-- don't leak to other members of the same multisig wallet.
CREATE TABLE IF NOT EXISTS cosigner_labels (
  device_id   TEXT        NOT NULL,
  wallet_id   TEXT        NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  idx         INT         NOT NULL,
  label       TEXT        NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, wallet_id, idx)
);

CREATE INDEX IF NOT EXISTS cosigner_labels_wallet_idx ON cosigner_labels(wallet_id);

-- wallet_members
CREATE TABLE IF NOT EXISTS wallet_members (
  wallet_id       TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  device_id       TEXT NOT NULL,
  account_index   INT NOT NULL DEFAULT -1,
  label           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (wallet_id, device_id, account_index)
);

CREATE INDEX IF NOT EXISTS wallet_members_device_idx ON wallet_members(device_id);

-- wallet_addresses
CREATE TABLE IF NOT EXISTS wallet_addresses (
  wallet_id       TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
  address_type    TEXT NOT NULL,           -- 'receive' or 'change'
  address_index   INT  NOT NULL,           -- BIP-32 child index (0, 1, 2, ...)
  address         TEXT NOT NULL,           -- derived Bitcoin address (bc1q..., tb1q..., etc.)
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (wallet_id, address_type, address_index)
);

CREATE INDEX IF NOT EXISTS wallet_addresses_address_idx ON wallet_addresses(address);
CREATE INDEX IF NOT EXISTS wallet_addresses_wallet_idx  ON wallet_addresses(wallet_id);
