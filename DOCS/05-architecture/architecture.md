# Architektura: komponenty, API a use‑casy

> Cíl: Android bitcoin aplikace s **coin‑control** a **multisig**. Všechny podpisy probíhají na **Trezoru**. Backend komunikuje s **Bitcoin blockchain** přes veřejné **Mempool.space API** (nevyžaduje vlastní node).

---

## 1) Hlavní komponenty

### 1.1 Zařízení / klient

* **Trezor**

  * Uchovává seed (BIP-39, novější verze SLIP-39 TODO).
  * Podepisuje **PSBT** (BIP-174). Model T umí pamatovat *multisig policy* pro ověřování. TODO (zjistit, jak tohle pořádně funguje a jak podpora jiných trezorů).
  * Identita pro backend: **master fingerprint** + xpub pro dané derivace.
  * Volitelná passphrase vytváří hidden wallet.
  * Používá **Trezor Connect Mobile** přes Trezor Suite (deeplink).

* **Android App**

  * UI (Jetpack Compose), coin-control, přehled multisigů, PSBT workflow.
  * **Trezor Connect Mobile flow:**
    * Appka otevře Trezor Suite Mobile přes deeplink (`https://connect.trezor.io/...`).
    * Suite zobrazí výzvu k potvrzení na Trezoru a po úspěchu zavolá zpět **deeplink** do appky (`bitcoinwallet://trezor-callback?...`).
    * Appka dostane JSON `{ success, payload: { fingerprint, xpub, path, device_model, device_label, ... }}`.
  * Párování účtu = identifikace Trezoru přes backend pomocí jeho master fingerprintu a odvozeného xpubu (end-point `/auth/trezor/login`).
  * Podepisování transakcí:
    * Backend připraví PSBT (`/wallets/{id}/tx/prepare`).
    * Appka pošle PSBT do Trezor Suite (Trezor Connect), získá podepsanou PSBT.
    * Podepsanou PSBT pošle zpět na backend (`/wallets/{id}/tx/submit`).
  * Transport: HTTPS → **API Gateway** (Android používá `MobileSigner` klienta postaveného na Ktoru).



### 1.2 Cloud backend

* **API Gateway** – vstupní REST API, autentizace/autorizační tokeny, rate‑limit, audit.
* **Auth & Pairing Service** – párování Trezoru ⇆ účet v aplikaci (device\_id), správa JWT/refresh.
* **Wallet Registry (SoT)** – jediný zdroj pravdy pro peněženky (deskriptory, multisig policy, členové, cosigneři).
* **Wallet Importer** – import/export policy/deskriptorů (Sparrow/Specter/BSMS/descriptor strings). TODO, zjistit jestli je potřeba toto rešit
* **Explorer Service** – čtení UTXO/historie/fee/chain info (cache), ZMQ invalidace.
* **PSBT Builder/Bridge** – stavba PSBT, coin selection, fee, finalize, broadcast.
* **Multisig Coordinator** – sleduje stav PSBT u multisigů (K‑z‑N), orchestrace podpisů, notifikace.
* **Notification Service** – FCM/webhooky (nevim jestli bude potřeba, spíše ne).
* **Blockchain Client (Mempool.space)** – HTTP klient pro komunikaci s veřejným Mempool.space API. Poskytuje UTXO, TX history, fee estimates, broadcast. Nevyžaduje vlastní Bitcoin node.
* **Infra úložiště**: PostgreSQL (perzistence), Redis (cache/rate‑limit), S3 (PSBT blobs), Observability (Prometheus/Grafana/Loki/Jaeger).

> **Poznámka:** Podepisování transakcí probíhá **výhradně na telefonu** přes Trezor Connect Mobile (deeplink do Trezor Suite). Backend nikdy nemá přístup k privátním klíčům ani k Trezoru.

### 1.3 Blockchain Data Source (Mempool.space)

* **Mempool.space API** – veřejné REST API pro Bitcoin blockchain data.
  * Mainnet: `https://mempool.space/api/`
  * Testnet: `https://mempool.space/testnet/api/`
* **Poskytuje**:
  * UTXO pro adresy: `GET /address/:addr/utxo`
  * TX historie: `GET /address/:addr/txs`
  * Fee estimates: `GET /v1/fees/recommended`
  * Broadcast TX: `POST /tx` (raw hex)
  * Account discovery: kontrola `tx_count` pro adresy derivované z xpub
* **Výhody**: Žádná infrastruktura, žádná údržba, vysoká dostupnost, podpora testnet.

---

## 2) Microservices – detail a API

### 2.1 API Gateway

* **Role**: jediný veřejný vstup; ověřuje JWT, aplikuje rate‑limits a audit; směruje na vnitřní služby.
* **Auth**: Bearer JWT; device‑scope (vázaný na `device_id`).
* **Veřejné endpointy (zatím jen příklady, bude jich více TODO)**

  * `POST /api/v1/auth/pairing/start` → zahájí pairing flow (viz UC‑01)
  * `POST /api/v1/auth/token/refresh` → nové access JWT
  * `GET  /api/v1/wallets` → seznam peněženek pro aktuální device
  * `POST /api/v1/wallets` → vytvoření singlesig/multisig (předá Wallet Registry)
  * `POST /api/v1/wallets/import` → import policy/descriptor (předá Wallet Importer)
  * `GET  /api/v1/wallets/{id}/utxos` → data z Exploreru
  * `POST /api/v1/psbt` → vytvoření PSBT (Bridge)
  * `POST /api/v1/psbt/{id}/submit` → přijetí podepsané PSBT z appky (Bridge)
  * `POST /api/v1/psbt/{id}/broadcast` → broadcast (Bridge → Mempool.space API)
* **Spojení**
  * API Gateway → Auth & Pairing Service: vydání/obnova tokenů přes pairing s Trezorem.
  * API Gateway → Wallet Registry: CRUD nad peněženkami a přidělování adres.
  * API Gateway → Explorer Service: čtení UTXO/historie/fee.
  * API Gateway → PSBT Bridge: stavba/finalizace PSBT.
  * API Gateway → Multisig Coordinator: dotazy na stav podpisů / registrace účasti 
  * API Gateway → PostgreSQL: zápis auditních záznamů.

### 2.2 Auth & Pairing Service

* **Role**: Zajišťuje párování Trezoru s backendem a vystavení tokenů pro ověřený přístup. 
* **Funkce**:
  * Párování Trezoru (mobilní varianta):
    * Android appka si přes Trezor Connect Mobile vyčte `fingerprint`, `xpub`, `derivationPath`, `deviceModel`, `deviceLabel`.
    * Přes MobileSigner zavolá `POST /auth/trezor/login`.
    * Auth služba podle fingerprintu a xpubu založí/aktualizuje záznam zařízení (`device_id`) a případně i uživatele.
  * Tokeny a autentizace:
    * Po úspěšném loginu vygeneruje krátkodobý access token (JWT) a dlouhodobý refresh token.
    * Tokeny jsou svázány s `device_id` a uživatelem.
  * Správa zařízení:
    * Eviduje známé Trezory (model, firmware, fingerprint).
    * Umožňuje ověřit, že zařízení odpovídá záznamu v systému.
* **API** (mobilní flow – první verze)
  * `POST /auth/trezor/login`
    * Vstup: `{ fingerprint, xpub, derivationPath, deviceModel, deviceLabel }` (z Trezor Connect).
    * Výstup: `{ accessToken, refreshToken?, user: { id, displayName, trezorFingerprint, wallets[] } }`.
  * `POST /auth/token/refresh`
    * Vstup: `{ refreshToken }`.
    * Výstup: `{ accessToken }`.
* **Spojení**
  * Auth → PostgreSQL: perzistence devices, users, refresh tokenů, klíčů pro JWT.
  * Auth → Wallet Registry: (volitelně) vytvoření výchozí watch-only wallet pro nově spárovaný Trezor.
  * Auth → API Gateway: Gateway volá Auth pro login a refresh a vrací odpověď appce.


### 2.3 Wallet Registry (Source of Truth)

* **Role**: Účel: Uchovává všechny definice peněženek (single i multisig) v podobě watch‑only záznamů. Je to hlavní databáze, která určuje, jak peněženky vypadají, kdo do nich patří a jak se počítají adresy.
* **Funkce**
  * Deskriptory + checksum, network, birth\_height, label.
  * Multisig policy (M, N, script type, account index, BIP‑67 pořadí).
  * **Cosigner fragments**: `fingerprint`, `origin_path` (např. `48'/0'/0'/2'`), `xpub_root, tady zatim TODO
  * **Wallet membership**: přiřazení `device_id` k peněžence (+ možná TODO `cosigner_idx`).
* **API TODO**
  * `POST /wallets` → vytvoření (single/multisig) z parametrů nebo z importu.
  * `GET  /wallets?device_id=…` → všechny wallet, které „vidí“ dané zařízení (auto‑discovery podle cosigner fragmentu).
  * `POST /wallets/{id}/members/attach` → připoj device k wallet (když se shoduje cosigner fragment).
* **Spojení**
  * Wallet Registry → PostgreSQL — zdroj pravdy pro wallets/cosigners/members
  * Wallet Registry → Explorer/Bridge/Signer — čtou deskriptory/členství

### 2.4 Wallet Importer5

* **Role**: Wallet Importer zajišťuje, že systém umí přijmout peněženky z různých externích nástrojů (např. Sparrow, Bitcoin Core, ..) a převést je do jednotného interního formátu, který používá Wallet Registry.
* **Funkce**:
  * Rozpoznání formátu:
    * Automaticky detekuje typ importu – může jít o descriptor string, BSMS JSON nebo export ze Specter/Sparrow. 
  * Normalizace dat:
    * Převede importovaný obsah do jednotné podoby. 
    * Seřadí cosignery podle BIP-67, sjednotí derivace a ověří strukturu. 
  * Validace:
    * Ověří kontrolní součty (descriptor checksum), síť, typ skriptu a počet signérů (M-of-N). 
    * Při chybě (např. nesprávná derivace nebo duplikovaný xpub) vrátí detailní hlášku. 
  * Integrace s Registry:
    * Po úspěšné normalizaci vytvoří nebo aktualizuje záznam v Wallet Registry. 
    * Výstupem jsou dva deskriptory (external a internal) a jednotná definice politiky (canonical policy) TODO.
* **API TODO**
  * `POST /wallets/import` → vstup: descriptor/policy export, výstup: canonical policy + dva deskriptory.

### 2.5 Explorer Service

* **Role**: Rychlé a škálovatelné READ endpointy pro peněženky: UTXO, historie, zůstatek, odhad poplatků a základní chain info. Používá **Mempool.space API** jako primární zdroj dat.
* **Funkce**:
  * Primární zdroj: **Mempool.space API** – UTXO, TX historie, fee estimates.
  * Account discovery: derivuje adresy z xpub a kontroluje `tx_count > 0` přes Mempool API.
  * Lokální cache: Redis (hot cache) pro snížení počtu API volání.
  * Projekce do PG: trvalé projekce UTXO/tx-history pro rychlé filtry, stránkování a agregace.
* **API** TODO
  * `GET /wallets/{id}/utxos`
  * `GET /wallets/{id}/history?limit=&from=`
  * `GET /fees/estimates`
  * `GET /chaininfo`
* **Data**: Redis (hot cache), PG (projekce na transakce/UTXO).
* **Spojení**
  * Explorer → Wallet Registry: získání descriptor setu a členství; Příklad: „Načti ext/int descriptor pro wallet X“. 
  * Explorer → Mempool.space API: UTXO, TX historie, fee estimates, account discovery.
  * Explorer → Redis: hot cache výsledků 
  * Explorer → PostgreSQL: projekce historie/utxo

### 2.6 PSBT Builder/Bridge

* **Role**: Tvoří, spravuje a připravuje PSBT (Partially Signed Bitcoin Transaction) pro podepisování a odesílání. Zajišťuje kompletní workflow od výběru vstupů (coin selection) až po finální broadcast transakce do sítě.
* **Funkce**: TODO celé pořádně překontrolovat
  * Tvorba PSBT (/psbt)
    * Načte definici peněženky z Wallet Registry (deskriptory). 
    * Načte UTXO z Exploreru. 
    * Provede coin selection – vybere vhodné vstupy podle cílové částky, poplatku a politiky (např. „minimize change“ nebo „max privacy“). 
    * Sestaví nekompletní PSBT s potřebnými metadaty (inputs, outputs, witnessUtxo, bip32 derivace, atd.). 
    * Vrací psbt_id + PSBT blob uložený (např. v S3 nebo Redis). 
  * Update PSBT (/psbt/{id}/update)
    * Umožňuje upravit vstupy nebo poplatky (např. při použití Coin Control). 
    * Znovu přepočítá poplatek a change výstup. 
  * Finalize (/psbt/{id}/finalize)
    * Finalizuje PSBT lokálně pomocí BitcoinJ/libwally. 
    * Zkontroluje, zda má transakce všechny potřebné podpisy (complete: true/false). 
    * Vrací hex a stav finální transakce. 
  * Broadcast (/psbt/{id}/broadcast)
    * Po complete=true odešle transakci do mempoolu přes **Mempool.space API** (`POST /tx`).
    * Mempool.space vrací txid při úspěchu.
* **API** TODO
  * `POST /psbt` – vstup: wallet\_id, outputs\[], optional inputs (coin‑control), fee policy. Výstup: `psbt_id`, PSBT blob.
  * `POST /psbt/{id}/update` – změna vstupů/fee (např. z Coin Control).
  * `POST /psbt/{id}/finalize` – finalizuje PSBT lokálně, řekne `complete: true/false`.
  * `POST /psbt/{id}/broadcast` – po `complete=true` pošle přes Mempool.space API.
* **Spojení**
  * Bridge → Wallet Registry: deskriptory, change index, policy 
  * Bridge → Explorer: UTXO/fee inputs 
  * Bridge → Mempool.space API: broadcast transakce (`POST /tx`)
  * Bridge → Multisig Coordinator: registrace PSBT a sběr podpisů 
  * Bridge → PostgreSQL: záznam o PSBT/TX
* Bezpečnostní omezení (Guard-rails):
  * Validace PSBT lokálně před broadcastem.
* **Závislosti**: Registry (deskriptory), Explorer (UTXO), Mempool.space (broadcast), Coordinator (multisig stav).

### 2.x MobileSigner (Android HTTP klient)

* **Role**: Tenký HTTP klient v Android appce (Ktor), který mluví s API Gateway a backendovými službami. Schovává URL endpointů a datové struktury.
* **Funkce**:
  * `loginWithTrezor(identity: TrezorDeviceIdentity): UserSession`
    * Volá `POST /auth/trezor/login`.
    * Tělo: `{ fingerprint, xpub, derivationPath, deviceModel, deviceLabel }`.
    * Odpověď: JWT access token + shrnutí uživatele a peněženek.
  * `preparePsbt(request: PreparePsbtRequest): PreparedPsbt`
    * Volá `POST /wallets/{walletId}/tx/prepare`.
    * Tělo: `{ amountSats, destinationAddress, feeRateSatsPerVb, selectedInputs[] }`.
    * Odpověď: `{ psbtId, psbtBase64 }`.
  * `submitSignedPsbt(request: SubmitSignedPsbtRequest): SubmitSignedPsbtResult`
    * Volá `POST /wallets/{walletId}/tx/submit`.
    * Tělo: `{ psbtId, signedPsbtBase64 }`.
    * Odpověď: `{ status, txId? }` (např. `accepted`, `broadcasted`, `waiting_for_cosigners`).
* **Spojení**
  * MobileSigner → API Gateway (HTTPS/JSON).
  * MobileSigner používá JWT access token jako `Authorization: Bearer ...`.


### 2.8 Multisig Coordinator

* **Role**: Zajišťuje koordinaci a sledování podpisů pro multisig PSBT transakce. V systému, kde je více signérů (např. 2‑z‑3), musí někdo spravovat, kolik podpisů už bylo přidáno, kolik ještě chybí a kdy lze transakci finalizovat.
* **Funkce**:
  * Každá nová PSBT se po vytvoření zaregistruje v Coordinatoru spolu s parametry M‑z‑N a wallet_id. 
  * Coordinator udržuje tabulku stavů: který cosigner již podepsal, čas podpisu, fingerprint a stav (pending, partial, complete). 
  * Jakmile některý z uživatelů přes Signer Service přidá podpis (addsig), Coordinator zaktualizuje PSBT záznam a vyhodnotí, zda je dosaženo požadovaného počtu podpisů (K‑z‑N). 
  * Pokud je PSBT kompletní (complete=true), notifikací informuje Bridge/Explorer, že lze provést finalizepsbt a následně broadcast.
* **API** TODO

  * `POST /ms/psbt/register` → registrace nového PSBT (wallet\_id, m/n)
  * `POST /ms/psbt/{id}/addsig` → přidán podpis (od Signeru)
  * `GET  /ms/psbt/{id}/status` → kolik chybí
* **Spojení**
  * Coordinator → PostgreSQL: stav podpisů (K‑z‑N), mapování signerů 
  * Coordinator → Redis: fronty/události/notifikace
  * Coordinator → API Gateway: dotazování z app

### 2.9 Notification Service TODO

* **Role**: FCM/webhooky při změně stavu PSBT, při příchozí transakci apod.
* **API**: `POST /notify/device` / `POST /notify/webhook`.

### 2.10 Blockchain Client (Mempool.space API)

* **Role**: HTTP klient pro komunikaci s veřejným **Mempool.space API**. Poskytuje všechny blockchain data bez nutnosti vlastního Bitcoin node.
* **Endpointy Mempool.space**:
  * `GET /api/address/:addr` → informace o adrese (tx_count, balance)
  * `GET /api/address/:addr/utxo` → seznam UTXO pro coin control
  * `GET /api/address/:addr/txs` → historie transakcí
  * `GET /api/v1/fees/recommended` → fee estimates (fastestFee, halfHourFee, hourFee)
  * `GET /api/tx/:txid` → detail transakce
  * `POST /api/tx` → broadcast raw hex transakce
* **Funkce**:
  * **Account discovery**: derivuje adresy z xpub (BIP-84/49/44), kontroluje `tx_count > 0`.
  * **UTXO list**: pro coin control vrací `{ txid, vout, value, status }`.
  * **Fee estimation**: vrací doporučené sat/vB pro různé priority.
  * **Broadcast**: odesílá finalizovanou TX jako raw hex.
* **Výhody**:
  * Žádná infrastruktura, žádná údržba
  * Vysoká dostupnost a spolehlivost
  * Podpora mainnet i testnet
* **Spojení**
  * Explorer Service → Mempool.space: UTXO, TX historie, fee estimates
  * PSBT Bridge → Mempool.space: broadcast transakce
* **Konfigurace**:
  * Mainnet: `https://mempool.space/api/`
  * Testnet: `https://mempool.space/testnet/api/`

### 2.11 Infra (PG/Redis/S3/Observability)

* **PG**: devices, wallets, wallet\_cosigners, wallet\_members, utxo/history projekce, audit, ms\_state.
* **Redis**: RL tokens, krátké cache, PSBT dočasné stavy.
* **S3**: PSBT blob.
* **Obs**: metriky, logy, trasy.
* Auth & Pairing	devices, tokens	párování Trezoru a JWT 
* Wallet Registry	wallets, cosigners, members	konfigurace peněženek 
* Explorer	utxo, history	projekce dat z blockchainu 
* Coordinator	psbt_state, signatures	stav podpisů 
* Audit	audit_log	logy akcí

---

## 3) Komunikace mezi službami (shrnutí)

| From → To                    | Protokol       | Účel                                     |
|------------------------------| -------------- | ---------------------------------------- |
| Android App → API Gateway    | HTTPS/JSON     | Všechny klientské akce                   |
| Android App ↔ Trezor Suite   | Deeplink       | Podepisování PSBT přes Trezor Connect    |
| API Gateway → AuthSvc        | HTTP           | Pairing, tokeny                          |
| API Gateway → WalletReg      | HTTP           | CRUD peněženek, členství                 |
| API Gateway → WalletImporter | HTTP           | Import policy/descriptor                 |
| API Gateway → Explorer       | HTTP           | Read (UTXO/history/fees/chain)           |
| API Gateway → Bridge         | HTTP           | Tvorba/úprava/finalize/broadcast PSBT    |
| API Gateway → MsCoordinator  | HTTP           | Registrace PSBT, stav                    |
| Explorer → Mempool.space     | HTTPS          | UTXO, TX historie, fee estimates         |
| Bridge → Mempool.space       | HTTPS          | Broadcast TX (`POST /api/tx`)            |

---

## 4) Datové modely

* **devices**: `(device_id, fingerprint, model, created_at)`
* **cosigners**: `(cosigner_id, fingerprint, origin_path, xpub_root)`
* **wallets**: `(wallet_id, wpid, network, type, script_type, m, n, account_index, birth_height, label)`
* **wallet\_cosigners**: `(wallet_id, idx, cosigner_id)`
* **wallet\_members**: `(wallet_id, device_id, cosigner_idx)`
* **psbt\_state**: `(psbt_id, wallet_id, created_by, created_at, complete, tx_hex?)`
* **audit**: `(ts, service, actor(device_id), action, payload_hash, ip/token)`


---

## 5) Use‑casy a **datové toky** mezi microservices

### UC‑01: Připojení Trezoru (pairing)

**Actors:** Uživatel

**Preconditions:** Trezor je připojen k telefonu (USB/Bluetooth) a Trezor Suite Mobile je nainstalována.

**Main Flow**

1. **App**: otevře deeplink do Trezor Suite Mobile pro získání seznamu účtů (`getAccountInfo` nebo `getPublicKey` pro všechny derivace).
2. **Trezor Suite**: zobrazí výzvu, uživatel potvrdí na Trezoru.
3. **Trezor Suite → App**: callback se seznamem účtů `{ accounts[]: { fingerprint, xpub, derivationPath, scriptType }, deviceModel, deviceLabel }`.
4. **App → Gateway → Explorer**: `POST /wallets/discover { descriptors[] }` – ověří, které účty mají aktivitu (UTXO/historie).
5. **Explorer → Node Proxy → Core**: pro každý descriptor zavolá `scantxoutset` nebo `listunspent` pro kontrolu aktivity.
6. **Explorer → App**: vrátí seznam aktivních účtů s balance `{ activeAccounts[]: { descriptor, balance, txCount } }`.
7. **App → Gateway**: `POST /auth/trezor/login` s daty z callbacku + informací o aktivních účtech.
8. **Gateway → AuthSvc → Registry**: založ/aktualizuj `device` a vytvoř watch-only wallety pro aktivní účty.
9. **Gateway → App**: vrátí `{ accessToken, refreshToken, user, wallets[] }`.
10. **App**: uloží tokeny a pokračuje na výběr účtu (UC‑08).

**Alternative**: 
- Uživatel odmítne na Trezoru → **App** zobrazí retry/cancel.
- Žádný účet nemá aktivitu → **App** nabídne vytvoření nového účtu (prázdná peněženka).

**Postconditions**: Zařízení je spárováno (device\_id), aktivní wallety jsou registrovány.

---

### UC‑02: Zobrazení zůstatku

**Actors:** Uživatel

**Preconditions:** Spárované zařízení, vybraná wallet (UC‑08).

**Main Flow**

1. **App → Gateway → Explorer**: `GET /wallets/{id}/utxos` + `GET /fees/estimates`.
2. **Explorer** (cache miss) → **Node Proxy → Core**: READ RPC; jinak z Redis/PG.
3. **App**: zobrazí sumu (BTC) + přepočet.

**Alternative**: chyba → App nabídne `Refresh`.

**Postconditions**: Zůstatek zobrazen a app ho periodicky refreshuje.

---

### UC‑03: Historie transakcí

**Main Flow**

1. **App → Gateway → Explorer**: `GET /wallets/{id}/history?limit=…`.
2. Explorer vrací seznam transakcí (PG projekce, případně doplněno z Core přes Node Proxy).

---

### UC‑04: Přijetí BTC (receive)

**Main Flow**

1. **App → Gateway → Registry**: `GET /wallets/{id}/address/next` → vygeneruj next index, vrátí adresu.
2. **App**: zobrazí adresu/QR.
3. (Volitelně) **App → Trezor Suite**: deeplink pro `getAddress` s `showOnTrezor=true` → uživatel ověří adresu **na Trezoru**.
4. Po přijetí vstupu **Explorer** přes ZMQ rychle signalizuje novou tx.

---

### UC‑05: Odeslání BTC (singlesig i multisig)

**Preconditions:** Dostatek UTXO; pro multisig budou podpisy v UC‑12.

**Main Flow** TODO udělat kontrolu

1. **App** vyplní cílové výstupy, mód výběru vstupů (Auto vs. Coin‑control).
2. **App → Gateway → Bridge**: `POST /psbt { wallet_id, outputs[], inputs? (z UC‑06), fee_policy }`.
3. **Bridge**: načte deskriptory (Registry), utxo (Explorer), provede coin selection, vytvoří PSBT.
4. **App** zobrazí rekapitulaci; otevře **Trezor Suite** přes deeplink s PSBT (`signTransaction`).
5. **Trezor Suite**: uživatel potvrdí na Trezoru, Suite vrátí podepsanou PSBT callbackem.
6. **App → Gateway → Bridge**: `POST /psbt/{id}/submit { signedPsbtBase64 }`.
7. **Bridge/MsCoordinator**: vyhodnotí stav (K‑z‑N), případně `finalizepsbt`.
8. Pokud **singlesig** nebo již komplet **multisig**: **Bridge** `finalizepsbt` (Node Proxy → Core) → `complete=true` → `sendrawtransaction`.
9. **Explorer** přes ZMQ/mempool brzy ukáže neconfirm TX v historii.

**Alternative**

* Uživatel podpis odmítne → PSBT zůstává v „pending“.
* `testmempoolaccept` odmítne (too‑low fee, double‑spend) → App ukáže chybu a dovolí upravit fee/vstupy.

---

### UC‑06: Coin Control (výběr vstupů)

**Main Flow**

1. **App → Gateway → Explorer**: `GET /wallets/{id}/utxos`.
2. Uživatel vybere konkrétní UTXO a poplatek.
3. **App → Bridge**: `POST /psbt { …, inputs: [txid:vout], fee_rate }` nebo `POST /psbt/{id}/update`.

**Alternative**: nedostatečný výběr → Bridge vrátí error s důvodem.

---

### UC‑07: Řazení UTXO

**Main Flow**

1. **App** mění kritéria (hodnota, stáří, etikety…).
2. **App → Explorer**: `GET /wallets/{id}/utxos?sort=age|value|…`.

---

### UC‑08: Výběr účtu (Select Account)


**Main Flow**

1. **App → Gateway → Registry**: `GET /wallets?device_id=me` → seznam wallet (single i multisig), které patří k mému Trezoru (auto‑discovery podle cosigner fragmentů).
2. Uživatel vybere wallet; App načte balance/history (UC‑02/03).

---

### UC‑09: Výběr multisig peněženky

**Main Flow**

1. **App → Registry**: `GET /wallets?type=multisig&device_id=me`.
2. Uživateli se zobrazí seznam s parametry (M‑z‑N) a balancí (Explorer).

---

### UC‑10: Přehled PSBT transakcí (multisig)

**Main Flow**

1. **App → MsCoordinator**: `GET /ms/psbt?wallet_id=…`.
2. Zobrazí seznam rozpracovaných PSBT, počty podpisů, datumy.

---

### UC‑11: Detail PSBT

**Main Flow**

1. **App → MsCoordinator/Bridge**: `GET /psbt/{id}`.
2. Zobrazí vstupy/výstupy, poplatek, stav podpisů.
3. Akce: **Sign** (UC‑12), **Export** (QR/text), **Broadcast** (pokud complete).

---

### UC‑12: Podepsání PSBT přes Trezor

**Main Flow**

1. **App**: otevře **Trezor Suite** přes deeplink s PSBT (`signTransaction`).
2. **Trezor Suite**: zobrazí detaily, uživatel potvrdí na Trezoru.
3. **Trezor Suite → App**: callback s podepsanou PSBT.
4. **App → Gateway → Bridge**: `POST /psbt/{id}/submit { signedPsbtBase64 }`.
5. **Bridge → MsCoordinator**: `POST /ms/psbt/{id}/addsig` (pro multisig).
6. **Bridge** (nebo MsCoordinator) zkusí `finalizepsbt` → pokud `complete=true`, označí jako připravené k broadcastu.

**Alternative**: odmítnuto / chyba komunikace → App nabídne retry.

---

### UC‑13: Broadcast podepsané transakce

**Preconditions**: `complete=true`.

**Main Flow**

1. **App → Bridge**: `POST /psbt/{id}/broadcast`.
2. **Bridge → Node Proxy → Core**: `sendrawtransaction` (po předchozím `testmempoolaccept`).
3. **Explorer** zachytí novou TX a zobrazí ji v historii.

**Alternative**: odmítnuto mempoolem → App nabídne úpravu fee.

---

### UC‑14: Import multisig peněženky

**Main Flow**

1. **App → WalletImporter**: `POST /wallets/import` (descriptor/policy export).
2. **Importer → Registry**: uloží canonical policy + deskriptory.
3. **Registry (auto‑discovery)**: pokud se aktuální device shoduje s některým cosignerem, přidá členství `wallet_members`.
4. **App**: peněženka je viditelná v seznamu.

**Alternative**: nevalidní descriptor/policy → 400 s chybou a tipem (BIP‑67 pořadí, špatná derivace apod.).

---

