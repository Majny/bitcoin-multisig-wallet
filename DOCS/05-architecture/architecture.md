# Architektura systému

> Android Bitcoin peněženka s podporou **coin-control**, **multisig** a **Trezor** hardware peněženky.
> Všechny podpisy probíhají výhradně na Trezoru. Backend komunikuje s Bitcoinovým blockchainem přes veřejné **Blockstream.info Esplora API**.

---

## 1. Přehled systému

Systém se skládá ze tří hlavních vrstev:

```
┌──────────────────────────────────────────────────────┐
│                   Android App                        │
│  Jetpack Compose UI · MobileSigner (Ktor) · Session  │
└────────────────────────┬─────────────────────────────┘
                         │ HTTPS/JSON
                         ▼
┌──────────────────────────────────────────────────────┐
│                   API Gateway :8080                  │
│        JWT ověření (JWKS) · routing · CORS           │
└──┬──────┬──────┬──────┬──────┬──────┬───────────────┘
   │      │      │      │      │      │
   ▼      ▼      ▼      ▼      ▼      ▼
auth   registry explorer psbt block- price
:8081  :8082   :8083   :8085 chain  :8087
                             :8086
                               │
                               ▼ HTTPS
                    Blockstream.info Esplora API
                    (nebo mempool.space)
```

Android appka nikdy nekomunikuje přímo s interními službami – vše prochází přes API Gateway. Trezor interaguje s appkou přes **Trezor Connect Mobile deeplink** (Trezor Suite Mobile app na telefonu).

Interní komunikace mezi microservicemi jde **přímo** (ne přes API Gateway):
- psbt-service → wallet-registry (wallet detail, adresy)
- psbt-service → blockchain-service (UTXO, raw tx, broadcast)
- explorer-service → wallet-registry (adresy)
- explorer-service → blockchain-service (balance, transakce)

---

## 2. Microservices

### 2.1 API Gateway `:8080`

Jediný veřejný vstupní bod. Ověřuje JWT tokeny pomocí JWKS (klíče servírované z auth-service), směruje požadavky na interní služby a zajišťuje CORS.

**Klíčové env proměnné:**

| Proměnná | Default (Docker) | Popis |
|---|---|---|
| `PORT` | `8080` | Naslouchací port |
| `AUTH_BASE_URL` | `http://auth-service:8081` | URL auth-service |
| `REGISTRY_BASE_URL` | `http://wallet-registry:8082` | URL wallet-registry |
| `EXPLORER_BASE_URL` | `http://explorer-service:8083` | URL explorer-service |
| `PSBT_BASE_URL` | `http://psbt-service:8085` | URL psbt-service |
| `BLOCKCHAIN_BASE_URL` | `http://blockchain-service:8086` | URL blockchain-service |
| `PRICE_BASE_URL` | `http://price-service:8087` | URL price-service |
| `JWT_ISSUER` | `wallet-auth` | Očekávaný issuer v JWT |
| `JWT_AUDIENCE` | `wallet-gateway` | Očekávaná audience v JWT |
| `JWKS_URL` | `http://auth-service:8081/auth/.well-known/jwks.json` | JWKS endpoint pro ověření podpisů |

**Veřejné (neautentizované) endpointy:**

| Metoda | Cesta | Přesměruje na |
|---|---|---|
| `POST` | `/auth/trezor/login` | auth-service + wallet-registry |
| `POST` | `/auth/token/refresh` | auth-service |

**Autentizované endpointy** (vyžadují `Authorization: Bearer <JWT>`):

| Metoda | Cesta | Přesměruje na |
|---|---|---|
| `GET` | `/wallets` | wallet-registry |
| `POST` | `/wallets` | wallet-registry |
| `POST` | `/wallets/import` | wallet-registry |
| `GET` | `/wallets/{id}/address` | wallet-registry |
| `GET` | `/explorer/wallet/{id}/balance` | explorer-service |
| `GET` | `/explorer/wallet/{id}/transactions` | explorer-service |
| `GET` | `/explorer/wallet/{id}/utxos` | explorer-service |
| `GET` | `/explorer/wallet/{id}/receive-address` | explorer-service |
| `GET` | `/explorer/tx/{txid}` | explorer-service |
| `GET` | `/explorer/fees` | explorer-service |
| `POST` | `/psbt` | psbt-service |
| `GET` | `/psbt/{id}` | psbt-service |
| `GET` | `/psbt/wallet/{walletId}` | psbt-service |
| `POST` | `/psbt/{id}/sign-trezor` | psbt-service |
| `POST` | `/psbt/{id}/broadcast-raw` | psbt-service |
| `GET` | `/psbt/{id}/signers` | psbt-service |
| `DELETE` | `/psbt/{id}` | psbt-service |
| `GET` | `/blockchain/address/{addr}` | blockchain-service |
| `GET` | `/blockchain/address/{addr}/utxos` | blockchain-service |
| `GET` | `/blockchain/address/{addr}/txs` | blockchain-service |
| `GET` | `/blockchain/address/{addr}/has-activity` | blockchain-service |
| `GET` | `/blockchain/fees` | blockchain-service |
| `GET` | `/blockchain/tx/{txid}` | blockchain-service |
| `POST` | `/blockchain/tx/broadcast` | blockchain-service |
| `GET` | `/price` | price-service |
| `GET` | `/price/convert` | price-service |

---

### 2.2 Auth Service `:8081`

Vystavuje JWT access tokeny (RSA, 15 minut platnost) a refresh tokeny (30 dní, rotace). Klíče generuje při startu a servíruje přes JWKS.

**Env proměnné:**

| Proměnná | Default | Popis |
|---|---|---|
| `PORT` | `8081` | Port |
| `JWT_ISSUER` | `wallet-auth` | Issuer claim |
| `JWT_AUDIENCE` | `wallet-gateway` | Audience claim |
| `JWT_DEV_ALLOW_GENERATE_KEYS` | `true` | Generuje RSA klíče při startu |

**Endpointy (interní, přes API Gateway):**

`POST /auth/trezor/login`
```json
// Request
{
  "fingerprint": "abcd1234",
  "xpub": "xpub6D4BDP...",
  "derivationPath": "m/84'/1'/0'",
  "deviceModel": "Trezor T",
  "deviceLabel": "Muj Trezor"
}
// Response
{
  "accessToken": "eyJ...",
  "refreshToken": "uuid",
  "user": { "id": "...", "displayName": "User", "trezorFingerprint": "abcd1234", "wallets": [] }
}
```

`POST /auth/token/refresh` — rotace refresh tokenu.

`GET /auth/.well-known/jwks.json` — JWKS pro ověření JWT v API Gateway.

> **Poznámka:** Login endpoint v API Gateway dělá více než jen auth: zároveň zaregistruje zařízení a automaticky vytvoří peněženku v wallet-registry (viz UC-01).

---

### 2.3 Wallet Registry `:8082`

Source of Truth pro peněženky. Ukládá deskriptory, derivuje adresy (BitcoinJ), eviduje zařízení, cosignery a členství v multisig peněženkách.

**Databáze:** `wallet_registry` (PostgreSQL)

**Schéma:**

```sql
/* Zařízení (Trezor) */
devices (
  device_id TEXT PK,
  fingerprint TEXT,
  model TEXT,
  label TEXT,
  created_at TIMESTAMPTZ
)

/* Peněženky */
wallets (
  wallet_id TEXT PK,
  network TEXT,
  type TEXT,              -- "SINGLE_SIG" | "MULTI_SIG"
  script_type TEXT,       -- "WPKH" | "TR" | "WSH"
  m INTEGER,              -- požadovaný počet podpisů (multisig)
  n INTEGER,              -- celkový počet cosignerů (multisig)
  account_index INTEGER,
  birth_height INTEGER,
  label TEXT,
  receive_descriptor TEXT,
  change_descriptor TEXT,
  created_at TIMESTAMPTZ
)

/* Cosigneři (multisig) */
cosigners (
  cosigner_id TEXT PK,
  fingerprint TEXT,
  origin_path TEXT,       -- "48'/1'/0'/2'" (BIP-48)
  xpub_root TEXT,
  created_at TIMESTAMPTZ
)

wallet_cosigners (wallet_id, idx, cosigner_id)  -- pořadí v deskriptoru
wallet_members  (wallet_id, device_id)           -- přiřazení zařízení

/* Odvozené adresy */
wallet_addresses (
  wallet_id TEXT,
  address_type TEXT,   -- "receive" | "change"
  address_index INTEGER,
  address TEXT,        -- bc1q... | tb1q...
  created_at TIMESTAMPTZ,
  PRIMARY KEY (wallet_id, address_type, address_index)
)
```

**Derivace adres:**
- **P2WPKH** (scriptType=WPKH): `key.toAddress(ScriptType.P2WPKH, network)` → `bc1q...` / `tb1q...`
- **P2WSH multisig** (scriptType=WSH): pubklíče všech cosignerů derivované z xpubů, seřazeny dle BIP-67 (lexikograficky), witness script `OP_M <pk1>...<pkN> OP_N OP_CHECKMULTISIG`, SHA-256 → bech32

**Gap limit:** 20 adres. Při `getNextReceiveAddress` vrátí první adresu s indexem, kde TX count = 0 (dotaz přes blockchain-service `has-activity`).

---

### 2.4 Explorer Service `:8083`

Aggreguje wallet-level data z wallet-registry (adresy) a blockchain-service (data z blockchainu). Počítá balance, transakce a UTXOs pro celou peněženku.

**Env proměnné:**

| Proměnná | Default | Popis |
|---|---|---|
| `PORT` | `8083` | Port |
| `REGISTRY_URL` | `http://wallet-registry:8082` | Adresa registry |
| `BLOCKCHAIN_URL` | `http://blockchain-service:8086` | Adresa blockchain-service |

**Klíčové optimalizace:**
- `BlockchainClient` deduplikuje souběžné požadavky na stejnou adresu: druhý coroutine čeká na výsledek prvního místo vlastního HTTP volání.
- Odpovědi `getAddressInfo` jsou cachovány 60 sekund v `ConcurrentHashMap`.
- Před fetchováním UTXOs / transakcí se pre-filtrují aktivní adresy pomocí `getAddressInfo` (která je cachována a šetří API volání).

**Klasifikace transakcí:**
- `RECEIVED`: žádný vstup není moje adresa, ale alespoň jeden výstup ano.
- `SENT`: alespoň jeden vstup je moje adresa.
- `SELF`: všechny vstupy i výstupy jsou moje.
- Počet konfirmací: `max(tipHeight - blockHeight + 1, 1)`, `tipHeight` fetchován z `/blockchain/tip/height`.

**Endpointy (interní):**

| Metoda | Cesta | Popis |
|---|---|---|
| `GET` | `/api/v1/explorer/wallet/{id}/balance` | Balance (confirmed/unconfirmed/total, utxoCount) |
| `GET` | `/api/v1/explorer/wallet/{id}/transactions` | Tx historie s klasifikací |
| `GET` | `/api/v1/explorer/wallet/{id}/utxos` | Seznam UTXO |
| `GET` | `/api/v1/explorer/wallet/{id}/receive-address` | Další volná adresa (next index) |
| `GET` | `/api/v1/explorer/tx/{txid}` | Detail transakce s `isMine` flagy |
| `GET` | `/api/v1/explorer/fees` | Doporučené fee sazby |

---

### 2.5 PSBT Service `:8085`

Spravuje PSBT workflow: tvorba, podepisování (přes Trezor Connect), a broadcast. Implementuje BIP-174. Kód je rozdělen do 3 souborů:
- `PsbtBuilder.kt` — sestaví PSBT binárně (unsigned tx, per-input/output metadata)
- `PsbtEncoding.kt` — nízkoúrovňové kódování (varint, bech32, scriptPubKey, witness script)
- `TrezorParamsBuilder.kt` — generuje Trezor Connect JSON params z wallet dat

**Databáze:** `wallet_psbt` (PostgreSQL)

```sql
/* PSBT records — stores transaction data, signing status, and Trezor Connect params. */
psbts (
  id UUID PK,
  wallet_id VARCHAR,
  psbt_base64 TEXT,                 -- aktuální stav PSBT (BIP-174)
  status VARCHAR,                   -- "pending" | "signed" | "broadcast"
  tx_type VARCHAR,                  -- "send"
  required_sigs INTEGER,            -- M (z peněženky)
  current_sigs INTEGER,             -- počet sebraných podpisů
  total_output_sats BIGINT,         -- součet výstupů (bez change)
  estimated_fee_sats BIGINT,
  label VARCHAR,
  txid VARCHAR,                     -- vyplněno po broadcastu
  trezor_connect_params TEXT,       -- JSON TrezorConnectParams (bez refTxs)
  serialized_tx TEXT,               -- podepsaný raw tx hex (od posledního cosignera)
  created_at, updated_at, broadcast_at TIMESTAMPTZ
)

/* Signature records — tracks which cosigners have signed each PSBT. */
psbt_signatures (
  id UUID PK,
  psbt_id UUID FK,
  device_id VARCHAR,
  fingerprint VARCHAR,
  cosigner_index INTEGER DEFAULT 0, -- pozice cosignera v deskriptoru
  signed_at TIMESTAMPTZ,
  UNIQUE(psbt_id, cosigner_index)   -- jeden podpis na cosignera
)
```

**Odhad velikosti transakce (vBytes):**
- P2WPKH single-sig vstup: **68 vB**
- P2WSH multisig vstup: **57 + 73×M + 34×N vB**
- Výstup (P2WPKH/P2WSH): 31 vB
- Základní overhead: 10 vB
- Vzorec: `(počet_vstupů × vB_na_vstup + počet_výstupů × 31 + 10) × fee_rate`

**Coin selection (automatický):** Largest-first algoritmus. Vstupy jsou přidávány od největšího UTXO dokud součet >= (cílová částka + fee). UTXOs reservované jinými pending/signed PSBT jsou vyloučeny (prevence double-spend). Pokud zbývá drobný nad dust limit (546 sats), připočte change výstup.

**Coin selection (manuální):** Pokud request obsahuje `utxos` s adresami (coin control), dotáže se blockchainu jen na ty konkrétní adresy (fast path). Bez adres skenuje celou peněženku (slow path).

**PSBT stavový automat:**

```
pending ──sign-trezor──→ signed ──broadcast-raw──→ broadcast
   │                        │
   └────── delete ◄─────────┘
```

PSBT přejde do stavu `signed` když `currentSigs >= requiredSigs`.

**Endpointy (interní):**

| Metoda | Cesta | Popis |
|---|---|---|
| `POST` | `/psbt/create` | Vytvoří PSBT (coin selection, build BIP-174, Trezor Connect params) |
| `GET` | `/psbt/{id}` | Detail PSBT |
| `GET` | `/psbt/wallet/{walletId}` | Seznam PSBT pro peněženku (?status=pending) |
| `POST` | `/psbt/{id}/sign-trezor` | Přidá podpisy od jednoho cosignera (DER sigs z Trezor Connect) |
| `POST` | `/psbt/{id}/broadcast-raw` | Broadcastuje raw signed TX hex (z Trezor Connect serializedTx) |
| `GET` | `/psbt/{id}/signers` | Stav podpisů cosignerů (kdo podepsal, kdo zbývá) |
| `DELETE` | `/psbt/{id}` | Smaže PSBT a uvolní reservované UTXO |

**Klíčové detaily implementace:**
- `sign-trezor`: BIP-67 — podpis se umístí na správnou pozici v `multisig.signatures[]` podle lexikografického pořadí pubkeys, ne podle cosigner indexu.
- `trezor_connect_params` se ukládá **bez refTxs** (příliš velké). Při dalším podpisu se raw tx znovu stáhnou z blockchainu.
- `serialized_tx` se uloží jen u **posledního podpisu** (když Trezor vrátí kompletně podepsanou tx).
- Duplikátní podpis se kontroluje podle `cosigner_index`, ne `fingerprint` (jeden Trezor může podepisovat jako různí cosigneři přes různé BIP-48 accounty).

---

### 2.6 Blockchain Service `:8086`

Proxy na **Esplora-kompatibilní API** (Blockstream.info, mempool.space). Všechna blockchain data procházejí přes tuto jedinou vrstvu. Výměna providera nevyžaduje změnu v ostatních službách.

Služba podporuje **obě sítě současně** — mainnet i testnet. Volající služby (explorer-service, psbt-service) předávají query parametr `?network=mainnet|testnet` a blockchain-service vybere odpovídajícího HTTP klienta pomocí `clientFor(network)`.

**Env proměnné:**

| Proměnná | Default | Popis |
|---|---|---|
| `PORT` | `8086` | Port |
| `MAINNET_MEMPOOL_URL` | `https://blockstream.info/api` | URL Esplora API pro mainnet |
| `TESTNET_MEMPOOL_URL` | `https://mempool.space/testnet4/api` | URL Esplora API pro testnet |

**Rate limiting:**
- Semaphore s 5 paralelními požadavky.
- Při HTTP 429 (Too Many Requests): 1 sekunda pauza a jeden retry.

**Endpointy (interní):**

| Metoda | Cesta | Esplora endpoint |
|---|---|---|
| `GET` | `/api/v1/blockchain/address/{addr}` | `GET /address/{addr}` |
| `GET` | `/api/v1/blockchain/address/{addr}/utxos` | `GET /address/{addr}/utxo` |
| `GET` | `/api/v1/blockchain/address/{addr}/txs` | `GET /address/{addr}/txs` |
| `GET` | `/api/v1/blockchain/address/{addr}/has-activity` | `GET /address/{addr}` → `tx_count > 0` |
| `GET` | `/api/v1/blockchain/fees` | `GET /fee-estimates` |
| `GET` | `/api/v1/blockchain/tip/height` | `GET /blocks/tip/height` |
| `GET` | `/api/v1/blockchain/tx/{txid}` | `GET /tx/{txid}` |
| `GET` | `/api/v1/blockchain/tx/{txid}/hex` | `GET /tx/{txid}/hex` |
| `POST` | `/api/v1/blockchain/tx/broadcast` | `POST /tx` (raw hex body) |

> **Poznámka k fee estimates:** Blockstream/mempool Esplora vrací `{"1": 50.0, "3": 35.0, "6": 25.0, ...}` (target v blocích → sat/vB). Blockchain-service mapuje tyto hodnoty na `fastestFee`, `halfHourFee`, `hourFee`, `economyFee`, `minimumFee`.

---

### 2.7 Price Service `:8087`

Proxy na **CoinGecko API** pro ceny BTC ve fiat měnách.

**Env proměnné:**

| Proměnná | Default | Popis |
|---|---|---|
| `PORT` | `8087` | Port |
| `COINGECKO_BASE_URL` | `https://api.coingecko.com/api/v3` | CoinGecko API URL |

**Caching:** In-memory cache na 5 minut (CoinGecko free tier: ~10-30 req/min).

**Endpointy (interní):**

| Metoda | Cesta | Popis |
|---|---|---|
| `GET` | `/price?currencies=czk,usd,eur` | Aktuální ceny BTC |
| `GET` | `/price/convert?sats={n}&currency={c}` | Převod satoshi → fiat |

---

## 3. Android aplikace

Jetpack Compose, MVVM architektura (ViewModel + mutableStateOf), Ktor HTTP klient.

### 3.1 Navigace

**TrezorConnect graph** (stav: nepřihlášen)
- `trezor_connect` — uvítací obrazovka s tlačítkem Connect Trezor
- `trezor_resolve` — zpracování callbacku, přihlášení, fetch peněženek
- `trezor_select_account` — výběr aktivní peněženky

**Wallet graph** (stav: přihlášen, peněženka vybrána)
- `wallet_dashboard` — balance, tx historie, drawer
- `wallet_send` — odeslání BTC (coin control volitelný)
- `wallet_coin_control` — výběr UTXO
- `wallet_tx_sent/{amountSats}/{feeSats}` — potvrzení odeslání
- `wallet_tx_error` — chybová obrazovka
- `wallet_receive` — přijmout BTC (adresa + QR)
- `wallet_transaction_detail/{txId}` — detail transakce
- `wallet_multisig_list` — seznam multisig peněženek
- `wallet_multisig_detail/{walletId}/{name}/{m}/{n}` — detail multisig
- `wallet_psbt_list/{walletId}` — seznam PSBT transakcí
- `wallet_psbt_detail/{psbtId}` — podepisování / broadcast
- `wallet_import` — import peněženky z deskriptoru
- `wallet_qr_scanner` — skenování QR kódu (Bitcoin adresa)
- `wallet_settings` — nastavení (switch account, měna)

### 3.2 Klíčové komponenty

**SessionStore** — globální singleton stav:
```kotlin
var session: UserSession?                          // accessToken + user + wallets
var activeWalletId: String?                        // vybraná peněženka
var activeAccountIndex: Int?                       // BIP-48 account index (pro multisig cosigner matching)
val pendingSignedPsbt: StateFlow<String?>          // signed data ze Trezoru (serializedTx)
val pendingSignType: StateFlow<SignResultType?>    // SERIALIZED_TX | SIGNED_PSBT
val pendingTrezorSignatures: StateFlow<List<String>?> // per-input DER podpisy (multisig)
val preferredCurrency: StateFlow<String>           // "czk" | "usd" | "eur"
var pendingIdentity: TrezorDeviceIdentity?         // z Trezor deeplink callbacku
var pendingBatchXpubs: List<TrezorDeviceIdentity>  // 10 xpubů z account discovery
```

**TrezorDeeplinkLauncher** — spouštění Trezor Suite Mobile přes deeplink:
- `openGetPublicKeyBundle(context, paths, network)` → požádá o 10 xpubů naráz (account discovery)
- `openSignTransactionStructured(context, TrezorConnectParamsDto)` → `signTransaction` se strukturovanými inputs/outputs
- `openSignTransaction(context, psbtBase64, network)` → legacy fallback
- `openGetAddress(context, derivationPath, network)` → `getAddress` s `showOnTrezor=true`
- Coin pro mainnet: `"btc"`, pro testnet: `"tbtc"`
- Deeplink base URL: `https://connect.trezor.io/9/deeplink/1/`

**TrezorCallbackActivity** — přijímá deeplink callback z Trezor Suite:
- Auth callback (action=`auth`): parsuje xpub bundle → `SessionStore.pendingBatchXpubs`
- Sign callback (action=`sign`):
  - Extrahuje `serializedTx` → `SessionStore.pendingSignedPsbt`
  - Extrahuje per-input `signatures[]` → `SessionStore.pendingTrezorSignatures`
  - Vrátí se do MainActivity přes `FLAG_ACTIVITY_SINGLE_TOP`
- Compose screen detekuje změnu přes `LaunchedEffect(pendingSignedPsbt)` → zpracuje

**WalletApiClient** — HTTP klient pro API Gateway:
- Wallet: `listWallets()`, `importWallet()`
- Explorer: `getWalletBalance()`, `getWalletTransactions()`, `getWalletUtxos()`, `getReceiveAddress()`, `getFeeEstimates()`
- PSBT: `createPsbt()`, `signTrezor()`, `broadcastRawTx()`, `getPsbtDetail()`, `listPsbtsForWallet()`, `getSignerStatus()`
- Price: `getBitcoinPrices()`, `convertSatsToFiat()`

---

## 4. Infrastruktura

### 4.1 Docker Compose

```
Service            Port   Databáze
────────────────────────────────────
api-gateway        8080   —
auth-service       8081   (in-memory refresh store)
wallet-registry    8082   wallet_registry
explorer-service   8083   —
psbt-service       8085   wallet_psbt
blockchain-service 8086   —
price-service      8087   —
postgres           5432   wallet_registry, wallet_psbt
```

**Databáze** jsou inicializovány skriptem `init-db.sql` při prvním spuštění. Wallet-registry i psbt-service používají **Flyway** pro migrace.

### 4.2 Spuštění

```bash
cd wallet-backend
docker-compose up --build
```

Volitelně lze přepsat URL blockchain providerů v `.env` souboru (`MAINNET_MEMPOOL_URL`, `TESTNET_MEMPOOL_URL`).

---

## 5. Use-casy a datové toky

### UC-01: Připojení Trezoru (přihlášení)

**Preconditions:** Trezor Suite Mobile nainstalována na telefonu. Trezor odemčen.

**Flow:**

```
1. App → Trezor Suite deeplink
   openGetPublicKeyBundle(context, paths, "testnet")
   Požádá o 10 xpubů (m/84'/1'/0' až m/84'/1'/9') naráz

2. Trezor Suite → TrezorCallbackActivity
   Callback: array of { fingerprint, xpub, path, device_model }
   SessionStore.pendingBatchXpubs = [TrezorDeviceIdentity(...), ...]

3. App → POST /auth/trezor/login
   { fingerprint, xpub, derivationPath: "m/84'/1'/0'", deviceModel, deviceLabel }

4. API Gateway:
   a. auth-service.trezorLogin() → JWT access + refresh token
   b. registry.upsertDevice() → uloží device_id (UUID z fingerprint)
   c. buildSingleSigWalletCreate():
      - derivationPath "m/84'/1'/0'" → coinType=1 → network="testnet"
      - scriptType: purpose=84 → "WPKH"
      - receiveDescriptor: "wpkh([fp/84h/1h/0h]xpub.../0/*)"
      - walletId: "wallet-{fp}-testnet-WPKH-0"
   d. registry.createWallet() → idempotentní (re-login = skip)
   e. registry.attachMember() → přiřadí device k peněžence
   f. registry.listWallets(deviceId) → vrátí peněženky

5. Response: { accessToken, refreshToken, user: { wallets: [{...}] } }

6. SelectAccountScreen → uživatel vybere peněženku
   SessionStore.activeWalletId = walletId
   SessionStore.activeAccountIndex = accountIndex

7. Navigace na WalletDashboard
```

---

### UC-02: Zobrazení zůstatku

```
1. App → GET /explorer/wallet/{id}/balance

2. Explorer:
   a. registry.getAddresses(walletId) → všechny receive+change adresy
   b. Paralelně: blockchain.getAddressInfo(addr) pro každou adresu (cache 60s)
   c. Součet: confirmedBalance = Σ chain_stats.funded - spent
              unconfirmedBalance = Σ mempool_stats.funded - spent

3. Response: { confirmedSats, unconfirmedSats, totalSats, utxoCount, addressCount }

4. App → GET /price?currencies=czk,usd,eur
   Přepočet: sats × (btcPrice / 100_000_000)
```

---

### UC-03: Historie transakcí

```
1. App → GET /explorer/wallet/{id}/transactions?limit=50&offset=0

2. Explorer:
   a. getAddresses(walletId)
   b. Pre-filter: getAddressInfo(addr).txCount > 0 → jen aktivní adresy
   c. Paralelně: getAddressTransactions(addr) pro aktivní adresy
   d. Deduplikace: rawTxMap[txid] (jedna tx se může týkat více adres)
   e. Klasifikace každé TX:
      - RECEIVED: žádný input není "mine", alespoň 1 output je "mine"
      - SENT: alespoň 1 input je "mine"
      - SELF: všechny vstupy i výstupy jsou "mine"
   f. Confirmations: tipHeight - blockHeight + 1

3. Response: { transactions: [{ txid, type, amountSats, fee, confirmed, confirmations, ... }] }
```

---

### UC-04: Detail transakce

```
1. App → GET /explorer/tx/{txid}?walletId={id}

2. Explorer:
   a. getTransaction(txid) z blockchain-service → raw TX data
   b. getAddresses(walletId) → moje adresy
   c. Pro každý vstup/výstup: isMine = adresa ∈ moje adresy
   d. Výpočet: type, amountSats (přijato / odesláno), fee

3. Response: { txid, inputs[], outputs[], fee, confirmed, blockHeight, isMine flags }
```

---

### UC-05: Přijetí BTC (Receive)

```
1. App → GET /explorer/wallet/{id}/receive-address

2. Explorer:
   a. registry.getAddresses(walletId, "receive") → existující adresy
   b. Pro adresy od posledního indexu: blockchain.hasActivity(addr)
   c. Vrátí první adresu kde hasActivity=false (nebo next index)

3. App zobrazí adresu + QR kód

4. (Volitelné) Ověření na Trezoru:
   App → openGetAddress(context, "m/84'/1'/0'/0/{index}", "testnet")
   Trezor Suite zobrazí adresu na displeji Trezoru
```

---

### UC-06: Odeslání BTC (singlesig)

```
1. (Volitelné) Coin Control: App → GET /explorer/wallet/{id}/utxos
   Uživatel vybere konkrétní UTXO → selectedUtxos = [{ txid, vout, address }, ...]

2. App → POST /psbt { walletId, outputs, feeRate, utxos?, rbf, signerAccountIndex }

3. PSBT Service (POST /psbt/create):
   a. registry.getWallet(walletId) → descriptor, network, type, cosigners
   b. Pokud utxos prázdné → autoSelectUtxos (largest-first, exclude reserved)
      Pokud utxos s adresami → selectSpecificUtxos (fast path)
   c. Paralelně: fetch raw tx hex pro každý unikátní txid (pro PSBT_IN_NON_WITNESS_UTXO + Trezor refTxs)
   d. registry.getChangeAddress(walletId) → next change adresa
   e. PsbtBuilder.createPsbt():
      - Sestaví unsigned tx (version, inputs, outputs, locktime)
      - Per-input: PSBT_IN_NON_WITNESS_UTXO, PSBT_IN_WITNESS_UTXO, PSBT_IN_BIP32_DERIVATION
      - Change output: PSBT_OUT_BIP32_DERIVATION
      - Dust limit check: change < 546 sats → přidáno k fee
   f. TrezorParamsBuilder.build():
      - Pro každý UTXO → TrezorConnectInput (address_n, prev_hash, amount, script_type)
      - Pro výstupy → TrezorConnectOutput (address/address_n, amount, script_type)
      - refTxs → raw hex předchozích transakcí
   g. Uložení do DB (status="pending", trezorConnectParams bez refTxs)
   Response: { id, psbtBase64, estimatedFee, estimatedVsize, trezorConnectParams, signerCosignerIndex }

4. App → openSignTransactionStructured(context, trezorConnectParams)
   Deeplink na Trezor Suite s kompletními params (inputs, outputs, refTxs, coin)

5. Trezor Suite zobrazí detaily TX → uživatel potvrdí na Trezoru
   Callback → TrezorCallbackActivity:
   payload.serializedTx → SessionStore.pendingSignedPsbt

6. SendTransactionViewModel (LaunchedEffect detekuje změnu):
   → broadcastRawTx(psbtId, serializedTx)

7. PSBT Service (POST /psbt/{id}/broadcast-raw):
   a. blockchain-service → POST /tx (raw hex)
   b. Uloží txid, status="broadcast"

8. App naviguje na TransactionSentScreen
```

---

### UC-07: Odeslání BTC (multisig) — první cosigner

```
1. Stejné kroky 1-5 jako UC-06 (singlesig)
   Rozdíl: TrezorConnectParams obsahují multisig objekt v inputs/outputs
   (pubkeys všech cosignerů, m threshold, signatures=["","",""])

2. Trezor Suite vrátí:
   - signatures: ["304402...", "304402..."]  (DER per input)
   - serializedTx: "020000..."  (neúplně podepsaná tx — chybí M-1 podpisů)

3. TrezorCallbackActivity:
   SessionStore.pendingTrezorSignatures = signatures
   SessionStore.pendingSignedPsbt = serializedTx

4. SendTransactionViewModel:
   → POST /psbt/{id}/sign-trezor {
       signatures: ["304402...", ...],
       cosignerIndex: 0,
       fingerprint: "aabbccdd",
       signerAccountIndex: 0
     }

5. PSBT Service (POST /psbt/{id}/sign-trezor):
   a. Resolve signerAccountIndex → cosignerIndex (mapování BIP-48 account → pozice v deskriptoru)
   b. Duplicate check: cosignerIndex already signed? → 409 Conflict
   c. Najde signer's pubkey z xpubu → BIP-67 pozice v multisig.pubkeys
   d. Update TrezorConnectParams: signatures[bip67_position] = DER sig
   e. currentSigs++ → 1 < requiredSigs(2) → status zůstává "pending"
   f. Uloží do psbt_signatures: { cosigner_index=0 }

6. App zobrazí: "1/2 podpisů, čeká na dalšího cosignera"
```

---

### UC-08: Podepisování PSBT (druhý cosigner)

```
1. Cosigner 2 otevře PsbtListScreen → vidí pending PSBT
   App → GET /psbt/wallet/{walletId}?status=pending

2. Klikne na PSBT → PsbtDetailScreen
   App → GET /psbt/{id} → { status: "pending", currentSigs: 1, requiredSigs: 2, trezorConnectParams }
   App → GET /psbt/{id}/signers → kdo podepsal, kdo zbývá

3. Klikne "Podepsat":
   a. adjustTrezorParamsForSigner(): opraví address_n[2] na svůj BIP-48 account index
   b. Fetch čerstvé refTxs z blockchainu (v DB nejsou uložené)
   c. openSignTransactionStructured(params)
   Trezor Connect params obsahují existující podpisy: signatures=["","304402...",""]

4. Trezor Suite → uživatel potvrdí → callback:
   signatures + serializedTx (teď kompletně podepsaná tx)

5. PsbtDetailViewModel:
   → POST /psbt/{id}/sign-trezor { signatures, cosignerIndex: 1, signerAccountIndex: 2 }

6. PSBT Service:
   a. BIP-67 lookup → umístí podpis na správnou pozici
   b. currentSigs=2 >= requiredSigs=2 → status="signed"
   c. Uloží serializedTx (kompletně podepsaná tx)

7. App detekuje status="signed":
   → POST /psbt/{id}/broadcast-raw { txHex: serializedTx }
   → status="broadcast", txid uložen

8. App naviguje na TransactionSentScreen
```

---

### UC-09: Import multisig peněženky

```
1. App → POST /wallets/import
   {
     descriptor: "wsh(sortedmulti(2,[fp1/48h/1h/0h/2h]xpub1/0/*,[fp2/...]xpub2/0/*,[fp3/...]xpub3/0/*))",
     network: "testnet",
     label: "2-of-3 Multisig"
   }

2. Wallet Registry (DescriptorParser):
   a. Parsuje typ deskriptoru: wsh(sortedmulti) → MULTI_SIG, WSH
   b. Extrahuje cosignery: fingerprint, origin_path, xpub
   c. Detekuje network z coin_type v origin_path (1 = testnet)
   d. Sestaví receive + change deskriptor

3. WalletImporter:
   a. Idempotentní tvorba peněženky (přeskočí pokud existuje)
   b. Uloží cosignery, wallet_cosigners
   c. Pokud device_id odpovídá cosignerovi → attachMember()

4. Response: { success: true, isNew: true/false, walletId, wallet }

5. App → SelectAccountScreen → zobrazí novou multisig peněženku
```

---

### UC-10: Coin Control (výběr UTXO)

```
1. App → GET /explorer/wallet/{id}/utxos → seznam UTXO

2. Explorer:
   a. getAddresses(walletId)
   b. Pre-filter: getAddressInfo(addr).utxoCount > 0
   c. Paralelně: getAddressUtxos(addr) pro aktivní adresy
   d. Enrich: address, addressType, addressIndex, confirmed, blockHeight

3. CoinControlScreen:
   - UTXO řádky s checkboxy (toggle výběru)
   - Seřadit dle: AMOUNT | STATUS | ADDRESS

4. Uživatel vybere UTXOs → přejde na SendTransactionScreen
   selectedUtxos = [{ txid, vout, address }, ...]
```

---

### UC-11: Zobrazení adresy na Trezoru

```
1. ReceiveBtcScreen → tlačítko "Show on Trezor"
2. App → openGetAddress(context, derivationPath, walletNetwork)
   derivationPath = "m/84'/1'/0'/0/{addressIndex}"
3. Trezor Suite zobrazí adresu na Trezoru → uživatel vizuálně ověří
4. Callback → akce= "showAddress" → TrezorCallbackActivity ignoruje (jen zobrazení)
```

---

## 6. Podpora sítí (mainnet / testnet)

Systém podporuje **obě sítě současně** — mainnet i testnet. Síť se určuje automaticky z derivační cesty při přihlášení (coin_type `0` = mainnet, `1` = testnet) a ukládá se do `wallet.network`. Všechny služby pak pracují s konkrétní sítí na základě tohoto pole.

### 6.1 Jak funguje detekce sítě

| Komponenta | Mechanismus |
|---|---|
| `blockchain-service` | Dva oddělené HTTP klienty: `MAINNET_MEMPOOL_URL` a `TESTNET_MEMPOOL_URL`. `clientFor(network)` vybere správného klienta. |
| `wallet-registry` | Detekuje network z coin_type v derivační cestě: `'/1'/` → `"testnet"`, `'/0'/` → `"mainnet"` |
| `api-gateway/AuthRoutes.kt` | `buildSingleSigWalletCreate()` → coin_type z derivační cesty → `wallet.network` |
| `explorer-service` | Čte `wallet.network` z wallet-registry a předává ho blockchain-service jako query parametr |
| `psbt-service` | Čte `wallet.network` z wallet-registry a předává ho blockchain-service jako query parametr |
| Android `TrezorDeeplinkLauncher` | Coin pro mainnet: `"btc"`, pro testnet: `"tbtc"` |

### 6.2 Konfigurace blockchain-service

```yaml
# docker-compose.yml
blockchain-service:
  environment:
    MAINNET_MEMPOOL_URL: ${MAINNET_MEMPOOL_URL:-https://blockstream.info/api}
    TESTNET_MEMPOOL_URL: ${TESTNET_MEMPOOL_URL:-https://mempool.space/testnet4/api}
```

---

## 7. Bezpečnostní principy

- **Privátní klíče nikdy neopustí Trezor.** Backend nemá přístup k seed ani k privátním klíčům.
- **Watch-only wallet:** backend uchovává pouze xpub (veřejný klíč) a odvozené adresy.
- **JWT:** RS256, 15 minut platnost. Refresh token (UUID) rotuje při každém použití.
- **Podepisování:** Trezor Connect params jsou poslány na Trezor přes deeplink, Trezor zobrazí detaily transakce uživateli před podpisem. Backend nikdy nepodepisuje.
- **Multisig BIP-67:** podpisy umístěny na správnou pozici podle lexikografického pořadí pubkeys ve witness scriptu.
- **Double-spend prevence:** UTXOs reservované pending/signed PSBT jsou vyloučeny z coin selection.
- **Dust limit:** change output pod 546 sats se nepřidá — místo toho se přidá k fee.

---

## 8. Komunikace mezi službami (přehled)

| Caller | Volaná služba | Protokol | Účel |
|---|---|---|---|
| Android App | API Gateway | HTTPS/JSON | Všechny klientské akce |
| Android App ↔ Trezor Suite | Deeplink (Intent) | URL scheme | Podepisování, ověření adres, xpub export |
| API Gateway | Auth Service | HTTP/JSON | JWT, refresh |
| API Gateway | Wallet Registry | HTTP/JSON | CRUD peněženek, adresy |
| API Gateway | Explorer Service | HTTP/JSON | Balance, tx historie, UTXO |
| API Gateway | PSBT Service | HTTP/JSON | PSBT workflow |
| API Gateway | Blockchain Service | HTTP/JSON | Proxy k Esplora API |
| API Gateway | Price Service | HTTP/JSON | BTC ceny |
| Explorer Service | Wallet Registry | HTTP/JSON | Adresy peněženky |
| Explorer Service | Blockchain Service | HTTP/JSON | UTXO, TX data, tip height |
| PSBT Service | Wallet Registry | HTTP/JSON | Wallet detail, adresy, cosigneři |
| PSBT Service | Blockchain Service | HTTP/JSON | UTXO, raw tx hex, broadcast |
| Blockchain Service | Blockstream.info | HTTPS/JSON | Esplora API |
| Price Service | CoinGecko | HTTPS/JSON | BTC ceny |

---

## 9. Trezor Connect deeplink struktury

Komunikace s Trezor Suite Mobile probíhá přes Trezor Connect deeplink protokol. Appka otvírá URL ve formátu:

```
https://connect.trezor.io/9/deeplink/1/?method={method}&params={JSON}&callback={callbackURL}
```

- `method` — název Trezor Connect metody
- `params` — URL-encoded JSON s parametry metody
- `callback` — URL kam Trezor Suite vrátí výsledek (`bitcoinwallet://trezor-callback?id={requestId}&action={action}`)

### 9.1 getPublicKey (bundle)

Získání xpubů pro account discovery. Použito při přihlášení (UC-01).

**Request params:**
```json
{
  "bundle": [
    { "coin": "tbtc", "path": "m/84'/1'/0'", "showOnTrezor": false },
    { "coin": "tbtc", "path": "m/84'/1'/1'", "showOnTrezor": false },
    ...
  ]
}
```

**Response callback** (`action=auth`):
```json
{
  "success": true,
  "payload": [
    { "fingerprint": "abcd1234", "xpub": "tpubDC8a5...", "serializedPath": "m/84'/1'/0'" },
    { "fingerprint": "abcd1234", "xpub": "tpubDRiv...", "serializedPath": "m/84'/1'/1'" },
    ...
  ]
}
```

### 9.2 signTransaction (singlesig P2WPKH)

**Request params:**
```json
{
  "coin": "tbtc",
  "inputs": [
    {
      "address_n": [2147483732, 2147483649, 2147483648, 0, 3],
      "prev_hash": "a1b2c3d4...",
      "prev_index": 0,
      "amount": "100000",
      "script_type": "SPENDWITNESS"
    }
  ],
  "outputs": [
    { "address": "tb1q...", "amount": "90000", "script_type": "PAYTOADDRESS" },
    { "address_n": [2147483732, 2147483649, 2147483648, 1, 0], "amount": "9500", "script_type": "PAYTOWITNESS" }
  ],
  "refTxs": [
    { "hash": "a1b2c3d4...", "tx_hex": "020000000001..." }
  ]
}
```

### 9.3 signTransaction (multisig P2WSH)

Rozdíl oproti singlesig: inputs a change output obsahují `multisig` objekt.

**Request params (input):**
```json
{
  "address_n": [2147483696, 2147483649, 2147483648, 2147483650, 0, 2],
  "prev_hash": "a1b2c3d4...",
  "prev_index": 0,
  "amount": "85000",
  "script_type": "SPENDWITNESS",
  "multisig": {
    "m": 2,
    "pubkeys": [
      { "node": { "depth": 4, "fingerprint": 12345, "child_num": 0, "chain_code": "ab...", "public_key": "02ab..." }, "address_n": [0, 2] },
      { "node": { "depth": 4, "fingerprint": 67890, "child_num": 0, "chain_code": "cd...", "public_key": "03ef..." }, "address_n": [0, 2] },
      { "node": { "depth": 4, "fingerprint": 11111, "child_num": 0, "chain_code": "ef...", "public_key": "0299..." }, "address_n": [0, 2] }
    ],
    "signatures": ["", "", ""]
  }
}
```

Při druhém podpisu `signatures` obsahují existující podpisy: `["", "304402...", ""]`.

**Response callback** (`action=sign`):
```json
{
  "success": true,
  "payload": {
    "signatures": ["304402...", "304402..."],
    "serializedTx": "020000000001..."
  }
}
```

### 9.4 getAddress

Zobrazení adresy na displeji Trezoru pro vizuální ověření (UC-11).

**Request params:**
```json
{
  "coin": "tbtc",
  "path": "m/84'/1'/0'/0/5",
  "showOnTrezor": true
}
```

### 9.5 Datový tok: Backend → Trezor Connect params

```
Backend (TrezorParamsBuilder.build):
  wallet.cosigners[signerIdx].originPath = "48'/1'/0'/2'"
  → parseOriginPathToUint32 → [2147483696, 2147483649, 2147483648, 2147483650]

  Pro každý UTXO:
    originPath + [chain, addressIndex]
    → address_n = [2147483696, 2147483649, 2147483648, 2147483650, 0, idx]

  Pro change výstup:
    originPath + [1, changeIndex]
    → address_n = [2147483696, 2147483649, 2147483648, 2147483650, 1, changeIdx]

  Pro multisig:
    xpubToHDNode(cos.xpubRoot) → { depth, fingerprint, child_num, chain_code, public_key }
    multisig.pubkeys[i].address_n = [chain, index] (relativní k account xpubu)
```
