-- V4: Per-member label for multisig wallets.
-- Each device can give the same multisig wallet a different name.
ALTER TABLE wallet_members ADD COLUMN label TEXT;
