-- V1: PSBT storage for pending transactions (single-sig and multisig)

-- Hlavní tabulka pro rozpracované PSBT transakce
CREATE TABLE psbts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id       VARCHAR(255) NOT NULL,
    
    -- PSBT data (base64 encoded)
    psbt_base64     TEXT NOT NULL,
    
    -- Metadata
    status          VARCHAR(50) NOT NULL DEFAULT 'pending',  -- pending, signed, finalized, broadcast, failed
    tx_type         VARCHAR(50) NOT NULL DEFAULT 'send',     -- send, consolidate, etc.
    
    -- Pro multisig: kolik podpisů je potřeba a kolik máme
    required_sigs   INT NOT NULL DEFAULT 1,
    current_sigs    INT NOT NULL DEFAULT 0,
    
    -- Volitelný popis od uživatele
    label           VARCHAR(255),
    
    -- Výsledný txid po broadcastu
    txid            VARCHAR(64),
    
    -- Časové značky
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    broadcast_at    TIMESTAMPTZ
);

-- Tabulka pro sledování jednotlivých podpisů (kdo už podepsal)
CREATE TABLE psbt_signatures (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    psbt_id         UUID NOT NULL REFERENCES psbts(id) ON DELETE CASCADE,
    
    -- Identifikace podepisovatele
    device_id       VARCHAR(255) NOT NULL,
    fingerprint     VARCHAR(16) NOT NULL,
    
    -- Časová značka podpisu
    signed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    UNIQUE(psbt_id, fingerprint)
);

-- Indexy
CREATE INDEX idx_psbts_wallet_id ON psbts(wallet_id);
CREATE INDEX idx_psbts_status ON psbts(status);
CREATE INDEX idx_psbts_created_at ON psbts(created_at DESC);
CREATE INDEX idx_psbt_signatures_psbt_id ON psbt_signatures(psbt_id);
