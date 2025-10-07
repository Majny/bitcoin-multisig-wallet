# Architektura: komponenty, API a use‑casy

> Cíl: Android bitcoin aplikace s **coin‑control** a **multisig**. Všechny podpisy probíhají na **Trezoru**. Backend komunikuje s **Bitcoin Core** na RPi **výhradně přes Tor (.onion RPC)**.

---

## 1) Hlavní komponenty

### 1.1 Zařízení / klient

* **Trezor**

  * Uchovává seed (BIP‑39, novější verze SLIP-39 TODO).
  * Podepisuje **PSBT** (BIP‑174). Model T umí pamatovat *multisig policy* pro ověřování. TODO (zjistit, jak tohle pořádně funguje a jak podpora jiných trezorů)
  * Identita pro backend: **master fingerprint** + xpub pro dané derivace.
  * Volitelná passphrase vytváří hidden wallet.
  * Nejspíš se bude hodit pro připojení: https://trezor.io/guides/trezorctl/using-trezorctl-commands

* **Android App**

  * UI (Jetpack Compose), coin‑control, přehled multisigů, PSBT workflow.
  * Párování účtu = identifikace Trezoru přes backend pomocí jeho master fingerprintu a odvozeného xpubu
  * Transport: HTTPS → **API Gateway**.


### 1.2 Cloud backend

* **API Gateway** – vstupní REST API, autentizace/autorizační tokeny, rate‑limit, audit.
* **Auth & Pairing Service** – párování Trezoru ⇆ účet v aplikaci (device\_id), správa JWT/refresh.
* **Wallet Registry (SoT)** – jediný zdroj pravdy pro peněženky (deskriptory, multisig policy, členové, cosigneři).
* **Wallet Importer** – import/export policy/deskriptorů (Sparrow/Specter/BSMS/descriptor strings). TODO, zjistit jestli je potřeba toto rešit
* **Explorer Service** – čtení UTXO/historie/fee/chain info (cache), ZMQ invalidace.
* **PSBT Builder/Bridge** – stavba PSBT, coin selection, fee, finalize, broadcast.
* **Signer Service (HWI)** – práce s Trezorem přes HWI (fp/xpub, displayaddress, signpsbt).
* **Multisig Coordinator** – sleduje stav PSBT u multisigů (K‑z‑N), orchestrace podpisů, notifikace.
* **Notification Service** – FCM/webhooky (nevim jestli bude potřeba, spíše ne).
* **Node Proxy (Tor RPC)** – jediná služba, která mluví na Core přes **Tor SOCKS5** a onion RPC. Má **allowlist RPC** a ochrany (max tx size/feerate, HTTP/1.1 + Connection: close, retry/backoff, audit).
* **Infra úložiště**: PostgreSQL (perzistence), Redis (cache/rate‑limit), S3 (PSBT blobs), Observability (Prometheus/Grafana/Loki/Jaeger).

### 1.3 RPi s Bitcoin Core

* **Bitcoin Core (`bitcoind`)** – full node, `rpcbind=127.0.0.1`, `proxy=127.0.0.1:9050`, `rpcauth=…`, `txindex=1`.
* **Tor** – onion service pro RPC. Core není z internetu přímo dosažitelné.

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
  * `POST /api/v1/psbt/{id}/sign` → podpis (Signer)
  * `POST /api/v1/psbt/{id}/broadcast` → broadcast (Bridge → Node Proxy)
* **Spojení**
  * API Gateway → Signer Service: Aplikace zavolá Gateway, ta přepošle dotaz správné službě.
  * API Gateway → Auth & Pairing Service: vydání/obnova tokenů přes pairing s Trezorem.
  * API Gateway → Wallet Registry: CRUD nad peněženkami a přidělování adres.
  * API Gateway → Explorer Service: čtení UTXO/historie/fee.
  * API Gateway → PSBT Bridge: stavba/finalizace PSBT.
  * API Gateway → Multisig Coordinator: dotazy na stav podpisů / registrace účasti 
  * API Gateway → PostgreSQL: zápis auditních záznamů.

### 2.2 Auth & Pairing Service

* **Role**: Zajišťuje párování Trezoru s backendem a vystavení tokenů pro ověřený přístup. 
* **Funkce**:
  * Párování Trezoru:
    * Přes Signer Service získá fingerprint a xpub z připojeného Trezoru. 
    * Vytvoří záznam device_id v databázi devices(device_id, fingerprint, model, created_at). 
    * Slouží k tomu, aby backend dokázal rozpoznat konkrétní fyzický Trezor. 
  * Tokeny a autentizace:
    * Po úspěšném párování vygeneruje krátkodobý access token (JWT) a dlouhodobý refresh token. 
    * Tokeny jsou svázány s device_id. 
    * Slouží k autorizaci všech následných požadavků z aplikace. 
  * Správa zařízení:
    * Eviduje známé Trezory (model, firmware, fingerprint). 
    * Umožňuje ověřit, že zařízení odpovídá záznamu v systému.
* **API** (teoretický pokus TODO)
  * `POST /pairing/start` → vyžádá si od Signeru `enumerate`/`getxpub` (pro vybranou cestu), vytvoří `device_id`.
  * `POST /token/refresh` → vrátí access JWT.
* **Spojení**
  * Auth → Signer Service: zjištění identity zařízení (HWI enumerate/getxpub), Příklad: „Vyčti fingerprint a xpub z připojeného Trezoru“. 
  * Auth → PostgreSQL: perzistence device, klíčů pro JWT/refresh 
  * Auth → API Gateway: vrací výsledky (tokens) volajícímu


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

### 2.4 Wallet Importer

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

* **Role**: Rychlé a škálovatelné READ endpointy pro peněženky: UTXO, historie, zůstatek, odhad poplatků a základní chain info. Minimalizuje zátěž na Bitcoin Core díky lokální cache/indexům a ZMQ invalidaci (nvm jestli bude potřeba TODO).
* **Funkce**:
  * Primární zdroj: vlastní indexy/projekce a Redis (hot cache) – typické dotazy obsluhuje z paměti (např. GET /wallets/{id}/utxos nebo GET /fees/estimates). 
  * Projekce do PG: trvalé projekce UTXO/tx-history pro rychlé filtry, stránkování a agregace.
  * ZMQ invalidace: odebírá hashblock/rawtx (případně sequence) a okamžitě invaliduje/aktualizuje cache. 
  * Fallback na Core: při cache miss nebo chybějících datech volá Node Proxy → Core RPC (s allowlist metodami).
* **API** TODO
  * `GET /wallets/{id}/utxos`
  * `GET /wallets/{id}/history?limit=&from=`
  * `GET /fees/estimates`
  * `GET /chaininfo`
* **Data**: Redis (hot cache), PG (projekce na transakce/UTXO).
* **Spojení**
  * Explorer → Wallet Registry: získání descriptor setu a členství; Příklad: „Načti ext/int descriptor pro wallet X“. 
  * Explorer → Node Proxy: fallback/primární RPC na Core; Příklad: „getblockfilter/getrawtransaction přes Tor“. 
  * Explorer → Electrum/Esplora: rychlé čtení UTXO/historie 
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
    * Zavolá Core RPC finalizepsbt přes Node Proxy. 
    * Zkontroluje, zda má transakce všechny potřebné podpisy (complete: true/false). 
    * Vrací hex a stav finální transakce. 
  * Broadcast (/psbt/{id}/broadcast)
    * Po complete=true odešle transakci do mempoolu přes Node Proxy → Core (sendrawtransaction). 
    * Volitelně spustí testmempoolaccept pro validaci před broadcastem.
* **API** TODO
  * `POST /psbt` – vstup: wallet\_id, outputs\[], optional inputs (coin‑control), fee policy. Výstup: `psbt_id`, PSBT blob.
  * `POST /psbt/{id}/update` – změna vstupů/fee (např. z Coin Control).
  * `POST /psbt/{id}/finalize` – zavolá Core `finalizepsbt`, řekne `complete: true/false`.
  * `POST /psbt/{id}/broadcast` – po `complete=true` pošle přes Node Proxy `sendrawtransaction`.
* **Spojení**
  * Bridge → Wallet Registry: deskriptory, change index, policy 
  * Bridge → Explorer: UTXO/fee inputs 
  * Bridge → Node Proxy: testmempoolaccept/broadcast 
  * Bridge → Multisig Coordinator: registrace PSBT a sběr podpisů 
  * Bridge → PostgreSQL: záznam o PSBT/TX
* Bezpečnostní omezení (Guard-rails):
  * Validace přes testmempoolaccept před broadcastem.
* **Závislosti**: Registry (deskriptory), Explorer (UTXO), Node Proxy (RPC), Coordinator (multisig stav).

### 2.7 Signer Service (HWI)

* **Role**: Zajišťuje komunikaci s Trezorem připojeným k backendu přes USB. Používá rozhraní HWI – Hardware Wallet Interface, které umožňuje bezpečné podepisování transakcí.
* **Funkce**:
  * Každé volání probíhá přes HWI CLI (např. hwi --device fingerprint ...) nebo HWI knihovnu. 
  * Backend dostává pouze veřejné informace (xpub, fingerprint) a částečně podepsané PSBT. 
  * Signer funguje jako „proxy“ mezi PSBT Builderem a fyzickým zařízením.
* **API** TODO
  * `POST /hwi/identity` → `{ fingerprint, model }`
  * `POST /hwi/getxpub` body: `{ path }` → `{ xpub_root, origin, fingerprint }`
  * `POST /hwi/displayaddress` body: `{ descriptor, index }`
  * `POST /hwi/signpsbt` body: `{ psbt }` → `{ psbt_partial }`
* **Spojení**
  * Signer → Trezor (HWI / Connect / Android USB): operace identity, displayaddress, sign
  * Signer → Wallet Registry: validace, že PSBT odpovídá descriptorům 
  * Signer → PostgreSQL: kdo podepsal co a kdy 
  * Signer → S3: uložení PSBT blobu

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

### 2.10 Node Proxy (Tor RPC)

* **Role**: Bezpečný a izolovaný most mezi backendem a Bitcoin Core, který komunikuje výhradně přes Tor onion RPC. Slouží jako jediný schválený vstupní bod do Core a chrání ho před přímým přístupem z internetu.
* **Funkce**:
  * Node Proxy běží jako interní microservice, který směruje RPC požadavky přes SOCKS5 proxy na 127.0.0.1:9050 do onion služby Core (bitcoind). 
  * Backendové služby (Explorer, PSBT Bridge) komunikují s Node Proxy místo přímého volání Core. 
* **Allowlist RPC metod**: 
  * Node Proxy povoluje pouze omezenou sadu RPC příkazů – čtecí i zápisové operace jsou přísně řízené. 
  * READ: getblockchaininfo, getblockhash, getblock, getrawtransaction, scantxoutset (s limitem), estimatesmartfee. 
  * WRITE: pouze testmempoolaccept (ověření transakce) a sendrawtransaction (odeslání do mempoolu)
* **API (interní REST)** TODO
  * `POST /rpc` body: `{ method, params }` → odpověď 1:1 s Core.
  * `POST /tx/test` body: `{ hex }` → Core `testmempoolaccept`.
  * `POST /tx/broadcast` body: `{ hex }` → Core `sendrawtransaction`.
* **Ochrany**: rate‑limit (req/min, bytes/min), max tx size (např. 500 kB), .. TODO
* **Spojení**
  * Node Proxy → Tor (SOCKS5) → Bitcoin Core: jediný výstup do nody 
  * Node Proxy → Observability: RPC metriky/latence/chyby TODO

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
| API Gateway → AuthSvc        | HTTP           | Pairing, tokeny                          |
| API Gateway → WalletReg      | HTTP           | CRUD peněženek, členství                 |
| API Gateway → WalletImporter | HTTP           | Import policy/descriptor                 |
| API Gateway → Explorer       | HTTP           | Read (UTXO/history/fees/chain)           |
| API Gateway → Bridge         | HTTP           | Tvorba/úprava/finalize/broadcast PSBT    |
| API Gateway → Signer         | HTTP           | displayaddress, signpsbt, identity/xpub  |
| API Gateway → MsCoordinator  | HTTP           | Registrace PSBT, stav                    |
| Explorer → Node Proxy        | HTTP (interní) | Fallback Core RPC                        |
| Bridge → Node Proxy          | HTTP (interní) | `testmempoolaccept`/`sendrawtransaction` |
| Node Proxy → Core (RPi)      | Tor (SOCKS5)   | Onion RPC 8332                           |
| Explorer ← ZMQ (Core)        | SUB            | `hashblock`/`rawtx` invalidace cache     |

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

**Preconditions:** Trezor je fyzicky dostupný (pro backend Signer).

**Main Flow**

1. **App → Gateway**: `POST /auth/pairing/start`.
2. **Gateway → Signer**: `POST /hwi/identity` → `{ fingerprint, model }`.
3. **Gateway → Signer**: `POST /hwi/getxpub { path:"m/84h/0h/0h" }` pro singlesig account‑0; pro multisig `m/48h/0h/0h/2h`.
4. **Gateway → Registry**: založ/aktualizuj `device` a (pokud chce uživatel rovnou wallet) vytvoř z xpub watch‑only wallet.
5. **Gateway → AuthSvc**: vystav `device_id`, `access/refresh JWT`.
6. **App**: ukládá access token a pokračuje na výběr účtu (UC‑08).

**Alternative**: PIN/confirm selže → **App** zobrazí retry/cancel.

**Postconditions**: Zařízení je spárováno (device\_id), případně vytvořená/registrovaná wallet.

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

1. **App → Gateway → Registry**: `GET /wallets/{id}` (descriptor) → vygeneruj next index.
2. **App → Gateway → Signer**: `POST /hwi/displayaddress { descriptor, index }` → uživatel ověří adresu **na Trezoru**.
3. **App** zobrazí adresu/QR. Po přijetí vstupu **Explorer** přes ZMQ rychle signalizuje novou tx.

---

### UC‑05: Odeslání BTC (singlesig i multisig)

**Preconditions:** Dostatek UTXO; pro multisig budou podpisy v UC‑12.

**Main Flow** TODO udělat kontrolu

1. **App** vyplní cílové výstupy, mód výběru vstupů (Auto vs. Coin‑control).
2. **App → Gateway → Bridge**: `POST /psbt { wallet_id, outputs[], inputs? (z UC‑06), fee_policy }`.
3. **Bridge**: načte deskriptory (Registry), utxo (Explorer), provede coin selection, vytvoří PSBT.
4. **App** zobrazí rekapitulaci; **App → Gateway → Signer**: `POST /hwi/signpsbt { psbt }`.
5. **Signer**: po potvrzení na Trezoru vrátí **partial** podpisy.
6. **Bridge/MsCoordinator**: `POST /ms/psbt/{id}/addsig`; vyhodnotí stav (K‑z‑N).
7. Pokud **singlesig** nebo již komplet **multisig**: **Bridge** `finalizepsbt` (Node Proxy → Core) → `complete=true` → `sendrawtransaction`.
8. **Explorer** přes ZMQ/mempool brzy ukáže neconfirm TX v historii.

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

1. **App → Signer**: `POST /hwi/signpsbt { psbt }`.
2. Trezor zobrazí detaily, uživatel potvrdí.
3. **Signer → MsCoordinator**: `POST /ms/psbt/{id}/addsig`.
4. **Bridge** (nebo MsCoordinator) zkusí `finalizepsbt` → pokud `complete=true`, označí jako připravené k broadcastu.

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

