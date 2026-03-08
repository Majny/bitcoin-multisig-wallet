/* PSBT storage — pending transactions, signing status, and Trezor Connect params. */

CREATE TABLE psbts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id               VARCHAR(255) NOT NULL,
    psbt_base64             TEXT NOT NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'pending',
    tx_type                 VARCHAR(50) NOT NULL DEFAULT 'send',
    required_sigs           INT NOT NULL DEFAULT 1,
    current_sigs            INT NOT NULL DEFAULT 0,
    total_output_sats       BIGINT NOT NULL DEFAULT 0,
    estimated_fee_sats      BIGINT NOT NULL DEFAULT 0,
    label                   VARCHAR(255),
    txid                    VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    broadcast_at            TIMESTAMPTZ,
    trezor_connect_params   TEXT,
    serialized_tx           TEXT
);

/* Signature records — tracks which cosigners have signed each PSBT. */
CREATE TABLE psbt_signatures (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    psbt_id           UUID NOT NULL REFERENCES psbts(id) ON DELETE CASCADE,
    device_id         VARCHAR(255) NOT NULL,
    fingerprint       VARCHAR(16) NOT NULL,
    cosigner_index    INT NOT NULL DEFAULT 0,
    signed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(psbt_id, cosigner_index)
);

CREATE INDEX idx_psbts_wallet_id ON psbts(wallet_id);
CREATE INDEX idx_psbts_status ON psbts(status);
CREATE INDEX idx_psbts_created_at ON psbts(created_at DESC);
CREATE INDEX idx_psbt_signatures_psbt_id ON psbt_signatures(psbt_id);
