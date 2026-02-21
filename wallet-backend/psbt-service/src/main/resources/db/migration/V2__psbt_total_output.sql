-- V2: Add total_output_sats to psbts for displaying amount in PSBT list

ALTER TABLE psbts ADD COLUMN total_output_sats BIGINT NOT NULL DEFAULT 0;
