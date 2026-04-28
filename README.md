# Bitcoin Multisig Wallet — Bachelor's Thesis

**Author:** Jakub Dvořák
**Supervisor:** RNDr. Filip Zavoral, Ph.D.

Android application for advanced Bitcoin management — multisignature transactions
(M-of-N), coin control, and Trezor hardware wallet integration via Trezor Connect.
Backend is a set of Kotlin/Ktor microservices; private keys never leave the
hardware wallet.

---

## Project structure

| Directory | Description |
|---|---|
| `wallet-frontend/` | Android application (Kotlin, Jetpack Compose) |
| `wallet-backend/` | Microservices (Kotlin/Ktor, PostgreSQL, Docker Compose) |

---

## Running the backend

```bash
cd wallet-backend
docker compose up --build
```

All environment variables are set inline in `docker-compose.yml`, so no extra
configuration is needed for the standard run. To launch a single service
directly on the host (outside Docker), each module ships an `.env.example`
template — copy it to `.env` and adjust.

The API Gateway listens on port 8080.

## Running the frontend

Open `wallet-frontend/` in Android Studio and run on a device or emulator.

The backend URL is configured in `wallet-frontend/local.properties` (gitignored)
via the `api.gateway.base.url` key. A template is available in
`wallet-frontend/local.properties.example` — copy it to `local.properties`
and adjust. If the key is missing, the build falls back to
`http://10.0.2.2:8080/api/v1` (the Android emulator's alias for the host's
localhost), so the app works in the emulator with the Dockerised backend out
of the box.

For a real device on the same LAN, set:

```properties
api.gateway.base.url=http://192.168.0.100:8080/api/v1
```

After changing `local.properties`, run a Gradle sync and rebuild so the new
value propagates into `BuildConfig.API_GATEWAY_BASE_URL`.

---

## Requirements

- **Backend:** Docker Engine + Docker Compose
- **Frontend:** Android Studio with Android SDK (min API 24, target API 34)
- **Hardware wallet:** Trezor Safe 3 / 5 / 7 with current firmware, plus the
  Trezor Suite Mobile application installed on the Android device
