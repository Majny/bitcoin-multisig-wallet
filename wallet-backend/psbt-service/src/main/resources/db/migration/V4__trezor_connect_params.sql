-- V2: Store Trezor Connect params JSON for multisig re-signing
ALTER TABLE psbts ADD COLUMN trezor_connect_params TEXT;
