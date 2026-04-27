# Bitcoin Multisig Wallet — Bachelor's Thesis

**Author:** Jakub Dvořák
**Supervisor:** RNDr. Filip Zavoral, Ph.D.

---

## Assignment

The thesis designs and implements an Android application for advanced management of Bitcoin funds.

By "advanced management" we specifically mean:

- **Multisignature transactions** — support for M-of-N schemes (e.g. 2-of-3) per BIP-48 (HD derivation for multisig) and BIP-67 (canonical key sorting), using output descriptors in the `wsh(sortedmulti(...))` format.
- **Coin control** — manual selection of specific unspent transaction outputs (UTXOs) when building transactions, enabling fee optimisation.
- **Trezor hardware wallet integration** through the Trezor Connect deeplink API for mobile platforms; private keys never leave the device and the application never has access to them.

The work also surveys existing advanced Bitcoin wallets in terms of multisig support, coin control, and hardware wallet integration, and identifies their limitations on mobile platforms.

The application uses the PSBT format (BIP-174) for creating, signing, and distributing partially signed transactions, and supports native SegWit addresses (P2WPKH per BIP-84 for singlesig, P2WSH for multisig). The backend is a set of microservices written in Kotlin/Ktor that talk to public blockchain APIs. The security model guarantees that the server never holds private keys — all signing happens on the hardware wallet.

---

## Project structure

| Directory | Description |
|---|---|
| `wallet-frontend/` | Android application (Kotlin, Jetpack Compose) |
| `wallet-backend/` | Microservices (Kotlin/Ktor, PostgreSQL, Docker Compose) |
| `DOCS/` | Documentation — user stories, use cases, architecture, diagrams (in Czech) |

### Backend microservices

| Service | Port | Description |
|---|---|---|
| `api-gateway` | 8080 | Entry point, JWT authentication, routing |
| `auth-service` | 8081 | JWT token issuance, JWKS |
| `wallet-registry` | 8082 | Wallet management, address derivation, descriptor import |
| `explorer-service` | 8083 | Wallet-level data aggregation (balance, transactions) |
| `psbt-service` | 8085 | PSBT creation, signing, broadcast |
| `blockchain-service` | 8086 | Proxy to Blockstream / Mempool APIs |
| `price-service` | 8087 | BTC price (CoinGecko) |

### Frontend — key packages

| Package | Description |
|---|---|
| `core/api` | HTTP client, DTOs |
| `core/trezor` | Trezor Connect deeplink integration |
| `core/session` | Login state, active wallet |
| `feature/trezorconnect` | Trezor connection, account selection |
| `feature/wallet` | Dashboard, send/receive, coin control, multisig, PSBT workflow |

---

## Documentation

The longer documents under `DOCS/` are written in Czech, matching the language of the thesis itself:

- [Application overview](./DOCS/01-overview.md)
- [User stories](./DOCS/02-user-stories/us-overview.md)
- [System requirements](./DOCS/03-requirements/requirements-overview.md)
- [Use cases](./DOCS/04-use-cases/uc-overview.md)
- [Architecture](./DOCS/05-architecture/architecture.md)
- [Use case diagram](./DOCS/diagrams/UC/uc-overview.svg)

---

## Running the project

### Backend

```bash
cd wallet-backend
docker compose up --build
```

All environment variables are set inline in `docker-compose.yml`, so no extra configuration is needed for the standard run. If you want to launch a single service directly on the host (outside Docker), each module ships an `.env.example` template — copy it to `.env` and adjust as needed.

### Frontend

Open `wallet-frontend/` in Android Studio and run on a device or emulator.

The backend URL is configured in `wallet-frontend/local.properties` (gitignored) via the `api.gateway.base.url` key. **A template with all variants is available in `wallet-frontend/local.properties.example`** — copy it to `local.properties` and adjust. If the key is missing, the build falls back to `http://10.0.2.2:8080/api/v1` (the Android emulator's alias for the host machine's localhost), which means the app works in the emulator with the Dockerised backend out of the box.

For a real device on the same LAN, add or uncomment the following line in `local.properties`:

```properties
api.gateway.base.url=http://192.168.0.100:8080/api/v1
```

(Replace the IP with the address of the host running Docker.) After the change, a Gradle sync and rebuild propagates the new value into `BuildConfig.API_GATEWAY_BASE_URL`.
