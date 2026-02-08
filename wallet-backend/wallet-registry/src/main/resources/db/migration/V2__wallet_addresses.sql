-- ===== wallet_addresses =====
-- Stores derived Bitcoin addresses for each wallet.
-- Addresses are derived from the wallet's receive/change descriptor at creation time.
CREATE TABLE IF NOT EXISTS wallet_addresses (
    wallet_id       TEXT NOT NULL REFERENCES wallets(wallet_id) ON DELETE CASCADE,
    address_type    TEXT NOT NULL,           -- 'receive' or 'change'
    address_index   INT  NOT NULL,           -- BIP-32 child index (0, 1, 2, ...)
    address         TEXT NOT NULL,           -- derived Bitcoin address (bc1q..., bc1p..., etc.)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (wallet_id, address_type, address_index)
);

CREATE INDEX IF NOT EXISTS wallet_addresses_address_idx ON wallet_addresses(address);
CREATE INDEX IF NOT EXISTS wallet_addresses_wallet_idx  ON wallet_addresses(wallet_id);
