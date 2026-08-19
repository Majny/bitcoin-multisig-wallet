# Bitcoin Multisig Wallet - Bachelor's Thesis

**Author:** Jakub Dvořák
**Supervisor:** RNDr. Filip Zavoral, Ph.D.

Android app for multisig (M-of-N) Bitcoin transactions, coin control and Trezor
integration over Trezor Connect. Backend is Kotlin/Ktor microservices; private
keys never leave the hardware wallet.

---

## Quick start

### 1. Backend

```bash
cd wallet-backend
docker compose up --build
```

API Gateway listens on port `8080`.

### 2. Frontend - set the backend host

The examples below use `<HOST_IP>` as a placeholder for the backend host.
Substitute your own based on:

| Where Android runs | `<HOST_IP>` |
|---|---|
| Emulator on the same machine as Docker | `10.0.2.2` |
| Real device on the same LAN | LAN IP of the Docker host (e.g. `192.168.0.100`) |
| Real device over Tailscale / VPN | Tailnet IP of the Docker host (e.g. `100.64.0.10`) |

Put that IP in **two** places:

**a)** `wallet-frontend/local.properties` (Android Studio creates the file on
first project open) - add one line at the bottom:

```properties
api.gateway.base.url=http://<HOST_IP>:8080/api/v1
```

**b)** `wallet-frontend/app/src/main/res/xml/network_security_config.xml` -
replace the `<domain>` with the same IP (Android blocks cleartext HTTP to
anything not whitelisted here):

```xml
<domain includeSubdomains="true"><HOST_IP></domain>
```

Then in Android Studio: **File → Sync Project with Gradle Files**, then **Run**.

---

## Requirements

- **Backend:** Docker Engine + Docker Compose
- **Frontend:** Android Studio, Android SDK (min API 24, target 34)
- **Hardware wallet:** Trezor Safe 3 / 5 / 7 with current firmware, plus
  Trezor Suite Mobile installed on the Android device

---

## Project layout

| Directory | Description |
|---|---|
| `wallet-frontend/` | Android app (Kotlin, Jetpack Compose) |
| `wallet-backend/` | Microservices (Kotlin/Ktor, PostgreSQL, Docker Compose) |
