-- V5: Track cosigner_index in signatures for same-device multi-account multisig.
-- The old UNIQUE(psbt_id, fingerprint) fails when one Trezor signs as multiple cosigners.

ALTER TABLE psbt_signatures ADD COLUMN cosigner_index INT NOT NULL DEFAULT 0;

-- Drop the old fingerprint-based unique and replace with cosigner-based
ALTER TABLE psbt_signatures DROP CONSTRAINT psbt_signatures_psbt_id_fingerprint_key;
ALTER TABLE psbt_signatures ADD CONSTRAINT psbt_signatures_psbt_id_cosigner_idx_key UNIQUE (psbt_id, cosigner_index);
