# Bitcoin Multisig Wallet – Bakalářská práce

**Autor:** Jakub Dvořák
**Vedoucí:** RNDr. Filip Zavoral, Ph.D.

---

## Zadání

Práce se zabývá návrhem a implementací mobilní aplikace pro platformu Android, která umožňuje pokročilou správu Bitcoinových prostředků.

Pod pojmem pokročilá správa se rozumí zejména:

- **Multisignature transakce** — podpora schémat M-of-N (např. 2-of-3) dle standardů BIP-48 (HD derivace pro multisig) a BIP-67 (kanonické řazení klíčů), s využitím output deskriptorů ve formátu `wsh(sortedmulti(...))`.
- **Coin control** — možnost manuálního výběru konkrétních nepoužitých výstupů (UTXO) při tvorbě transakcí, což umožňuje optimalizaci poplatků.
- **Integrace hardwarové peněženky Trezor** prostřednictvím rozhraní Trezor Connect (deeplink API pro mobilní platformy), kde veškeré privátní klíče zůstávají na zařízení a aplikace s nimi nikdy nepřijde do styku.

Součástí práce je analýza existujících pokročilých Bitcoinových peněženek z hlediska podpory multisig schémat, coin control a integrace hardwarových peněženek, a identifikace jejich omezení na mobilních platformách.

Aplikace využívá formát PSBT (BIP-174) pro tvorbu, podepisování a distribuci částečně podepsaných transakcí a podporuje nativní SegWit adresy (P2WPKH dle BIP-84 pro singlesig, P2WSH pro multisig). Backend je realizován jako soustava mikroslužeb (Kotlin/Ktor) komunikujících s veřejnými blockchain API (Blockstream Esplora). Bezpečnostní model aplikace zajišťuje, že server nikdy nedisponuje privátními klíči, podepisování probíhá výhradně na hardwarové peněžence.

---

## Struktura projektu

| Složka | Popis |
|---|---|
| `wallet-frontend/` | Android aplikace (Kotlin, Jetpack Compose) |
| `wallet-backend/` | Mikroslužby (Kotlin/Ktor, PostgreSQL, Docker Compose) |
| `DOCS/` | Dokumentace — user stories, use cases, architektura, diagramy |

### Backend – mikroslužby

| Služba | Port | Popis |
|---|---|---|
| `api-gateway` | 8080 | Vstupní bod, JWT autentizace, routing |
| `auth-service` | 8081 | Vydávání JWT tokenů, JWKS |
| `wallet-registry` | 8082 | Správa walletů, derivace adres, import deskriptorů |
| `explorer-service` | 8083 | Agregace dat o walletu (zůstatek, transakce) |
| `psbt-service` | 8085 | Tvorba, podepisování a broadcast PSBT |
| `blockchain-service` | 8086 | Proxy na Blockstream/Mempool API |
| `price-service` | 8087 | Kurz BTC (CoinGecko) |

### Frontend – klíčové balíčky

| Balíček | Popis |
|---|---|
| `core/api` | HTTP klient, DTO |
| `core/trezor` | Trezor Connect deeplink integrace |
| `core/session` | Stav přihlášení, aktivní wallet |
| `feature/trezorconnect` | Připojení Trezoru, výběr účtu |
| `feature/wallet` | Dashboard, send/receive, coin control, multisig, PSBT |

---

## Dokumentace

- [Přehled aplikace](./DOCS/01-overview.md)
- [User Stories](./DOCS/02-user-stories/us-overview.md)
- [Požadavky na systém](./DOCS/03-requirements/requirements-overview.md)
- [Use Cases](./DOCS/04-use-cases/uc-overview.md)
- [Architektura](./DOCS/05-architecture/architecture.md)
- [Use Case diagram](./DOCS/diagrams/UC/uc-overview.svg)

---

## Spuštění

### Backend

```bash
cd wallet-backend
docker compose up --build
```

### Frontend

Otevřít `wallet-frontend/` v Android Studiu a spustit na zařízení/emulátoru.
