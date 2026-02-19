-- Init script pro vytvoření všech potřebných databází
-- Spouští se při prvním startu PostgreSQL kontejneru

-- Databáze pro autentizaci
CREATE DATABASE wallet_auth;

-- Databáze pro PSBT transakce
CREATE DATABASE wallet_psbt;

-- GRANT práva na všechny databáze
GRANT ALL PRIVILEGES ON DATABASE wallet_auth TO wallet;
GRANT ALL PRIVILEGES ON DATABASE wallet_psbt TO wallet;
GRANT ALL PRIVILEGES ON DATABASE wallet_registry TO wallet;
