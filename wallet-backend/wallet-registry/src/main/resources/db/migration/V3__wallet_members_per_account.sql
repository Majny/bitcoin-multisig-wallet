-- Per-account multisig import: track which account imported the wallet.
-- PK changes from (wallet_id, device_id) to (wallet_id, device_id, account_index).

-- Step 1: Add account_index column
ALTER TABLE wallet_members ADD COLUMN account_index INT NOT NULL DEFAULT -1;

-- Step 2: Copy cosigner_idx values (existing data) then drop cosigner_idx
UPDATE wallet_members SET account_index = COALESCE(cosigner_idx, -1);
ALTER TABLE wallet_members DROP COLUMN cosigner_idx;

-- Step 3: Change PK
ALTER TABLE wallet_members DROP CONSTRAINT wallet_members_pkey;
ALTER TABLE wallet_members ADD PRIMARY KEY (wallet_id, device_id, account_index);
