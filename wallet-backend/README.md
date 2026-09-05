# Bitcoin Wallet Backend

Microservice backend for a Bitcoin wallet with singlesig (P2WPKH) and multisig (P2WSH) support. Uses Trezor hardware wallet for signing.

See [`DOCS/05-architecture/architecture.md`](../DOCS/05-architecture/architecture.md) for full architecture documentation.

## Quick Start

```bash
docker-compose up --build
```

API gateway runs on `http://localhost:8080`.

## Services

| Service | Port | Description |
|---------|------|-------------|
| api-gateway | 8080 | Entry point, JWT auth, routing |
| auth-service | 8081 | JWT token issuance (RS256) |
| wallet-registry | 8082 | Wallet/address management |
| explorer-service | 8083 | Balance, transactions, UTXOs |
| psbt-service | 8085 | PSBT creation, signing, broadcast |
| blockchain-service | 8086 | Proxy to Blockstream/Mempool APIs |
| price-service | 8087 | BTC price from CoinGecko |
| postgres | 5432 | PostgreSQL 16 database |

## Build

```bash
./gradlew build          # build all modules
./gradlew :auth-service:run   # run single service locally
```

## Tech Stack

Kotlin, Ktor, PostgreSQL, Exposed, Flyway, bitcoinj, Docker
