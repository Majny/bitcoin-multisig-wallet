## 5.1 Overview

Cílem je vytvořit bezpečnou a auditovatelnou Bitcoin aplikaci pro Android, která podporuje **coin-control** i **multisig**, přičemž všechny transakce jsou podepisovány výhradně na **Trezoru** prostřednictvím **HWI (Hardware Wallet Interface)**.  
Aplikace funguje jako tenký klient – uživatelská logika a privátní klíče zůstávají mimo telefon.  

Architektura je rozdělena do tří hlavních vrstev:

1. **Android App (klient)** – uživatelské rozhraní pro práci se single-sig i multisig peněženkami, zajišťuje validaci vstupů, coin-control jako UI, zobrazuje stav transakcí a směruje uživatele k podpisu na Trezoru.  
   → nikdy nemá přístup k seedům ani privátním klíčům.

2. **Cloud Backend** – centrální aplikační vrstva.  
   - **API Gateway** – vystavuje REST API, řeší autentizaci, rate limiting a logování.  
   - **Blockchain Bridge** – sestavuje a finalizuje PSBT, provádí coin selection, výpočet fee a broadcast do sítě.  
   - **Explorer Adapter** – čte UTXO, historii a fee-rate z našeho Bitcoin Core.  
   - **Signer Service** – izolovaná služba, která přes HWI komunikuje s HW peněženkou a vrací podepsané PSBT.  
   - **Databáze** – ukládá watch-only deskriptory, multisig konfigurace, UTXO, historii a auditní logy.

3. **Raspberry Pi s Bitcoin Core** – plnohodnotný node, zdroj pravdy o blockchainu, poskytuje UTXO, historii a umožňuje broadcast. Přístupný z cloudu pouze přes **Tailscale VPN** (IP: `100.79.139.14`).

---

### Bezpečnostní model
- **Seed je vždy jen v Trezoru.** Ani telefon, ani backend ani DB nemají privátní klíče.  
- **Podepisování transakcí:** PSBT → Signer Service → HWI → Trezor.  
- **Komunikace:**  
  - App ↔ Cloud: vždy přes **HTTPS (TLS 1.3, volitelně cert pinning)**.  
  - Cloud ↔ RPi: šifrovaný tunel přes **VPN**.  
- **Audit:** všechny operace (create, sign, broadcast) jsou logované, bez ukládání citlivých dat.

---

**Diagram (komponentový přehled):** `./diagrams/components-detailed.puml`  
**Diagram (deployment):** `./diagrams/deployment.puml`  
**Diagram (trust boundaries):** `./diagrams/trust-boundaries.puml`

---


## 5.2 Komponenty

### 5.2.1 Android App (`app`)

#### Úloha komponenty v systému

Android aplikace je **nativní klient** napsaný v Kotlinu (UI v Jetpack Compose). Je to uživatelské rozhraní pro single-sig i multi-sig peněženky. Nepřistupuje přímo ani k Trezoru, ani k Raspberry Pi.

Hlavní odpovědnosti:

* sběr a validace vstupů (adresa, částka, volba UTXO),
* UI pro obsluhu obou módů (single / multisig),
* import a registrace multisig peněženek (descriptor, počet signatářů, fingerprinty),
* coin-control jako UI (uživatel vybírá vstupy z dat doručených backendem),
* zobrazení stavu transakcí (pending, kolik podpisů už bylo přidáno, ..),
* směrování uživatele k podepisování na jeho Trezoru.

---

#### Vnitřní členění aplikace

**1) Obrazovky (Compose UI)**

* **Dashboard** – přehled zůstatků a posledních transakcí.
* **Send** – formulář: adresa + částka, volba fee, coin-control (výběr UTXO), náhled transakce.
* **Receive** – zobrazení přijímací adresy + možnost ověření na Trezoru.
* **History** – seznam transakcí: potvrzené i pending, u pending transakcí ukazuje stav podpisů (např. „2/3 podepsáno“).
* **Multisig wallets** – import peněženky (descriptor), přehled uložených multisig konfigurací.
* **Multisig transaction detail** – detail pending transakce, seznam dosavadních signatářů, tlačítko „Podepsat na Trezoru“.
* **Settings** – měna.

---

**2) Logika v telefonu**

* **Validace vstupů** – kontrola formátu adresy, částky.
* **Coin-control** – pouze UI vrstva; skutečné poskládání transakce je na backendu.
* **Multisig management**:

  * import descriptoru,
  * zobrazení konfigurace (M z N, fingerprinty signatářů),
  * evidence, které peněženky má uživatel připojené.
* **Transakce**:

  * *single-sig*: Idle -> Náhled -> Potvrdit -> backend sestaví PSBT -> Signer Service (Trezor) -> Broadcast -> Hotovo/Chyba.
  * *multisig*: appka ví, že je třeba více podpisů → při otevření transakce zobrazí aktuální stav a nabídne podepsání, pokud uživatelův fingerprint ještě nechybí.

---

**3) HTTP klient (komunikace s backendem)**

* Implementace: **Ktor Client**.
* Všechna komunikace: **HTTPS (TLS)**.
* Každý request nese `Authorization: Bearer <token>` (JWT), `X-Client-Request-Id`, `X-App-Version`.
* **Idempotency-Key** u POST akcí („Odeslat“ nebude spouštět 2×).
* API endpointy:

  * `GET /balance`, `GET /utxo`, `GET /history`
  * `POST /tx/preview`, `POST /tx/create`, `POST /tx/sign`, `POST /tx/broadcast`
  * `GET /address?display=true`
  * `POST /wallets/multisig/import`, `GET /wallets/multisig`
  * `GET /tx/pending`, `GET /tx/{id}`, `POST /tx/{id}/sign`

---

**4) Lokální uložiště**

* **EncryptedSharedPreferences / DataStore** – preference (měna, téma, volby notifikací).
* **Dočasná cache** – poslední seznam UTXO a historie (TTL krátká, čistě pro offline zobrazení).

---

#### Stavové scénáře

**Single-sig odeslání:**

1. Uživatel vyplní formulář.
2. App volá `/tx/preview` → dostane náhled s fee/change.
3. Potvrdí → `/tx/create` → PSBT.
4. App požádá o podpis `/tx/sign` → Trezor ukáže údaje, uživatel potvrdí.
5. `/tx/broadcast` → backend odešle do sítě, app zobrazí txid.

**Multisig odeslání:**

1. Jeden uživatel vytvoří transakci (`/tx/create`).
2. Ostatní signatáři vidí v appce pending transakci (`GET /tx/pending`).
3. Otevřou detail → appka ví, zda jejich fingerprint už podepsal.
4. Pokud ne, nabídne „Podepsat na Trezoru“ (`POST /tx/{id}/sign`).
5. Backend shromažďuje partial signatures, dokud `current_sigs == required_sigs`.
6. Po dosažení M z N → backend finalize + broadcast.
7. Appka transakci přesune z pending do historie.

---

#### Bezpečnost a soukromí

* **Soukromí:** app neodesílá nic mimo naše API; coin-control i historie jsou watch-only.

---

#### Diskuze (proč takto / alternativy)

* **Coin-control jen jako UI** – reálné sestavení transakce probíhá v Blockchain Bridge, aby logika byla sdílená pro single i multisig.
* **Descriptor import pro multisig** – interoperabilita s Core/Specter/Sparrow. Ruční zadávání xpubů by bylo nepřátelské.
* **Stav podpisů sledovaný přes backend DB** – blockchain neukládá partial signatures, bez DB by se musely posílat QR kódy, což je nepraktické.
* **Ktor Client** – držíme jednotný Kotlin stack. Retrofit by byl taky možnost, ale přinesl by další knihovnu navíc.
* **Bez lokální DB** – app zůstává tenká, historie a UTXO se tahají z backendu. Pokud by někdy byl požadavek na offline historii, dá se doplnit read-only cache.

### 5.2.2 API Gateway (`cloud-backend`, vrstva API)

#### Úloha komponenty v systému

API Gateway je centrální vstupní bod pro mobilní aplikaci i případné další klienty. Vystavuje **REST API** a směruje požadavky na vnitřní komponenty (Blockchain Bridge, Explorer Adapter, Signer Service). Plní funkci bezpečnostní brány, která kontroluje přístup, omezuje zneužití a poskytuje observabilitu.

Hlavní odpovědnosti:

* **REST API rozhraní** – endpointy pro práci s účty, UTXO, transakcemi, adresami.
* **Autentizace a autorizace** – validace JWT tokenů, řízení přístupu.
* **Rate limiting a throttling** – omezení počtu požadavků za čas pro prevenci DoS.
* **Centralizované logování a metriky** – každý request je logován se stavem, časem, session id; metriky pro monitoring.
* **Směrování** – přeposílání požadavků na Blockchain Bridge (PSBT a coin selection), Explorer Adapter (UTXO, historie) nebo Signer Service (HWI operace).
* **Chybový model** – sjednocená struktura chybových odpovědí (HTTP status + JSON s kódem a popisem).
* **Verzování API** – všechny endpointy jsou verzované (`/api/v1/...`).

---

#### Vnitřní členění a workflow

1. **Příjem požadavku**

   * Mobilní aplikace volá HTTPS endpoint.
   * API Gateway ověří TLS a zkontroluje JWT.

2. **Autorizace**

   * Token obsahuje informace o uživateli/sessi a expiraci.
   * Pokud je token neplatný nebo expirovaný, vrací se 401 Unauthorized.

3. **Rate limiting**

   * Každý uživatel i IP adresa mají limit požadavků (např. 60/min).
   * Při překročení limitu vrací 429 Too Many Requests.

4. **Směrování na backend služby**

   * Po validaci požadavku gateway zavolá příslušnou službu:

     * Blockchain Bridge (`/tx/create`, `/tx/preview`, `/tx/broadcast`)
     * Explorer Adapter (`/balance`, `/utxo`, `/history`)
     * Signer Service (`/tx/sign`, `/address?display=true`)

5. **Agregace a odpověď**

   * Gateway zpracuje odpověď, doplní metadata (trace id) a vrátí JSON klientovi.

---

#### Bezpečnostní opatření

* Všechny requesty přes **HTTPS (TLS 1.3)**, s možností **certificate pinningu** na klientovi.
* JWT tokeny krátkodobé (např. 15 minut), obnova pomocí refresh tokenu.
* Idempotency-Key u POST požadavků pro ochranu před duplicitami.
* Detailní audit log všech změnových operací (create, sign, broadcast).
* Oddělení veřejného API od interních služeb – mobilní app vidí jen gateway, nikdy přímo Bridge/Signer/Explorer.

---

#### Diskuze (proč takto / alternativy)

* **Ktor** – lehký Kotlin framework, jednoduchá integrace, jeden stack pro mobil i server.

  * *Alternativy*: Spring Boot (robustní ekosystém, ale větší overhead a boilerplate), Node/Express (rychlé prototypování, ale jiný jazyk a horší typová bezpečnost).

* **JWT** – vhodné pro mobilní klienty, umožňuje krátké relace, snadno se ověřuje na gateway.

  * *Alternativy*: OAuth2/OpenID – vhodné pro enterprise a federované identity, ale pro MVP zbytečně složité. Připravujeme hooky pro pozdější rozšíření.

* **Rate limiting na gateway** – chrání vnitřní služby před zahlcením.

  * *Alternativy*: Bez rate limiting → jednodušší, ale vyšší riziko DoS.

* **Centralizované logování a metriky** – umožňuje sledovat výkon a rychle reagovat na incidenty.

  * *Alternativy*: Lokální logování v každé službě → méně přehledné.

---

#### Shrnutí

API Gateway je centrální vstupní bod systému. Zajišťuje autentizaci, autorizaci, omezení zneužití a směrování požadavků na vnitřní komponenty. Díky tomu mobilní aplikace komunikuje jen s jedním rozhraním, zatímco skutečná logika (Bridge, Explorer, Signer) zůstává izolovaná a bezpečně spravovaná.

### 5.2.3 Blockchain Bridge (`blockchain-bridge` nebo balíček v `cloud-backend`)

#### Úloha komponenty v systému

Blockchain Bridge je vrstva, která zajišťuje kompletní práci s Bitcoin transakcemi na úrovni backendu. Vytváří a spravuje **PSBT (Partially Signed Bitcoin Transactions)**, provádí **coin selection** a výpočet poplatků, a nakonec transakce finalizuje a vysílá do sítě prostřednictvím Bitcoin Core uzlu na Raspberry Pi.

Hlavní odpovědnosti:

* **Tvorba PSBT** – na základě požadavků z API (adresy příjemců, částky, vybraná UTXO) sestaví transakci ve formátu PSBT podle BIP174/370.
* **Coin selection** – algoritmus pro výběr vhodných UTXO tak, aby byly splněny požadavky na částku a zároveň byla optimalizována výše poplatku.
* **Výpočet poplatku (fee)** – dynamicky na základě fee-rate z node/exploreru a velikosti transakce.
* **Change output** – generace change adresy z příslušného descriptoru.
* **Finalizace transakce** – spojení všech podpisů do finální podoby raw transaction.
* **Broadcast** – odeslání finalizované transakce do Bitcoin sítě přes RPC rozhraní našeho Bitcoin Core.

---

#### Vnitřní členění a workflow

1. **Příprava dat**

   * Backend přijme požadavek z API Gateway (`/tx/create`).
   * Z DB/Exploreru získá UTXO, které uživatel vybral (coin-control) nebo které vybere algoritmus.

2. **Coin selection**

   * Použití jednoduchého algoritmu (např. Branch and Bound nebo knapsack).
   * Respektuje minimální velikost výstupu (dust limit).
   * Pokud appka předala konkrétní UTXO (coin-control), algoritmus je použije.

3. **Výpočet fee a sestavení PSBT**

   * Spočítá se velikost transakce (v bytech) a výsledná fee-rate.
   * Generuje se výstup na adresu příjemce + případně change výstup.
   * Výsledek: nekompletní PSBT předaná do Signer Service.

4. **Finalizace**

   * Po obdržení dostatečného počtu podpisů (single-sig = 1, multisig = M z N) Bridge finalizuje PSBT.
   * Zkontroluje, že všechny vstupy jsou podepsané a že struktura transakce je validní.

5. **Broadcast**

   * Finální raw tx se pošle přes Bitcoin Core RPC (`sendrawtransaction`).
   * Výsledek (txid) se uloží do DB a vrátí zpět do API Gateway.

---

#### Bezpečnostní opatření

* **PSBT standard (BIP174/370)** – minimalizuje riziko chyb a zajišťuje kompatibilitu s HW peněženkami.
* **Žádné privátní klíče v Bridge** – pracuje jen s watch-only deskriptory.
* **Validace vstupů** – kontrola, že všechny adresy a částky jsou správně zformátované a nedochází k nevalidním výstupům.
* **Audit** – loguje se každé vytvoření a broadcast transakce (bez privátních dat).

---

#### Diskuze (proč takto / alternativy)

* **PSBT jako standard** – interoperabilita s HWI/Trezor/Bitcoin Core, snadná auditovatelnost.

  * *Alternativy*: raw tx flow → zranitelné, těžší na údržbu a méně auditovatelné.

* **Coin selection** – pro MVP jednoduchý algoritmus (BnB/knapsack) + možnost ručního výběru v aplikaci.

  * *Alternativy*: pokročilé heuristiky (např. pro vyšší ochranu soukromí, coinjoin-friendly selection, optimalizace dlouhodobých fee). Ty lze doplnit ve verzi 2.

* **Broadcast přes vlastní node** – kontrola nad tím, co se do sítě vysílá, vyšší důvěryhodnost.

  * *Alternativy*: využití veřejných broadcast API (rychlejší start, ale závislost na třetí straně, možná cenzura).

---

#### Shrnutí

Blockchain Bridge je klíčová komponenta pro práci s transakcemi. Připravuje PSBT, provádí coin selection, spočítá poplatky, finalizuje transakce a vysílá je do sítě. Díky oddělení od Signer Service nikdy nepracuje s privátními klíči a zajišťuje auditovatelné a bezpečné flow.

### 5.2.4 Explorer Adapter (`explorer-adapter` nebo balíček v `cloud-backend`)

#### Úloha komponenty v systému

Explorer Adapter slouží jako vrstva pro čtení dat z blockchainu. Je zodpovědný za poskytování informací o **UTXO**, historii transakcí a aktuálních **fee-rate** potřebných pro sestavování transakcí. Komunikuje přímo s naším Bitcoin Core uzlem běžícím na Raspberry Pi, případně s alternativními backendy (Electrum, Esplora).

Hlavní odpovědnosti:

* **Čtení UTXO** – dotazování na dostupné výstupy pro dané adresy/deskriptory.
* **Historie transakcí** – poskytování seznamu potvrzených i nepotvrzených transakcí.
* **Fee estimation** – získávání doporučených poplatků pro různé target bloky (`estimatesmartfee`).
* **Monitoring mempoolu** – možnost sledovat nepotvrzené transakce pro rychlejší UX.
* **Abstrakce** – sjednocuje rozhraní, ať už zdrojem dat je Bitcoin Core RPC, Electrum server nebo Esplora API.

---

#### Vnitřní členění a workflow

1. **Požadavek z API Gateway**

   * Aplikace volá např. `GET /balance` nebo `GET /history`.
   * API Gateway předá požadavek Explorer Adapteru.

2. **Dotaz na node/explorer**

   * Primárně Bitcoin Core RPC (`listunspent`, `gettransaction`, `estimatesmartfee`).
   * Alternativně lze využít Electrum server (`blockchain.scripthash.listunspent`) nebo Esplora (`/address/{addr}/utxo`).

3. **Zpracování výsledků**

   * Normalizace dat do jednotného interního formátu (UTXO, Transaction, FeeEstimate).
   * Ukládání do cache/DB pro rychlejší odezvu a snížení zátěže na node.

4. **Odpověď klientovi**

   * API Gateway vrátí mobilní aplikaci čistá data (balance, historie, UTXO, fee).

---

#### Bezpečnostní opatření

* Použití vlastního Bitcoin Core = plná kontrola nad daty, odolnost proti cenzuře.
* Přístup jen přes VPN z cloudu na RPi.
* Validace odpovědí – kontrola, že data jsou ve správném formátu a konzistentní.
* Audit logy dotazů pro detekci podezřelých vzorců.

---

#### Diskuze (proč takto / alternativy)

* **Bitcoin Core RPC na vlastním RPi** – nejvyšší důvěryhodnost, žádná závislost na třetích stranách.

  * *Alternativy*:

    * **Veřejné explorer API** – rychlý start, ale znamená důvěru v externího poskytovatele (riziko cenzury, výpadků).
    * **Electrum server** – lehčí varianta, stále může běžet u nás, vhodná pro horizontální škálování.
    * **Esplora** – moderní REST API, ale nutno provozovat vlastní instanci pro zachování důvěryhodnosti.

---

#### Shrnutí

Explorer Adapter je čtecí vrstva, která sjednocuje přístup k blockchainovým datům. Primárně čte přímo z našeho Bitcoin Core uzlu, čímž zajišťuje plnou kontrolu a důvěryhodnost. Je připravený i na alternativní backendy (Electrum, Esplora) a poskytuje API pro balance, UTXO, historii a doporučené fee.

### 5.2.5 Signer Service (`signer-service`)

#### Úloha komponenty v systému

Signer Service je samostatná komponenta, která zajišťuje bezpečnou a izolovanou komunikaci s Trezorem prostřednictvím nástroje **HWI (Hardware Wallet Interface)**. Slouží jako prostředník mezi backendem a fyzickým zařízením. Mobilní aplikace nikdy nekomunikuje přímo s HW peněženkou – veškeré interakce probíhají přes tuto službu. **USB přístup existuje pouze na straně serveru, nikdy v telefonu.**

Hlavní odpovědnosti:

* Zajištění přístupu k HW peněžence připojené přes USB (v serverovém prostředí).
* Volání příkazů HWI jako externího procesu.
* Získání xpubů pro konfiguraci watch-only účtů.
* Ověření adresy na zařízení (`--display`).
* Podepisování transakcí – přijímá PSBT, předává je HWI a vrací částečně nebo plně podepsané PSBT.
* Podpora více typů HW peněženek (Trezor, Ledger, Coldcard, …) díky HWI.
* V testovacím prostředí možnost používat **trezor-user-env** (emulátor Trezoru) místo fyzického zařízení.

---

#### Vnitřní členění a workflow

1. **Rozhraní (API pro backend)**

   * `POST /signer/enumerate` → HWI `enumerate` (detekce zařízení).
   * `POST /signer/getxpub` → HWI `getxpub`.
   * `POST /signer/getaddress` → HWI `getaddress --display`.
   * `POST /signer/signpsbt` → HWI `signpsbt`.

2. **Komunikace s HWI**

   * HWI běží jako externí proces, Signer Service jej volá a zpracovává JSON výstupy.
   * Výsledky jsou validovány a převedeny na formát, který backend očekává.
   * Každé volání je auditováno (čas, operace, výsledek).

3. **Bezpečnostní opatření**

   * Služba běží v izolovaném VM nebo kontejneru.
   * Přístup pouze k USB na serveru, žádný přístup k síti.
   * Detailní logování chyb a neúspěšných operací.
   * Žádný přístup k databázi nebo k aplikační logice.

4. **Testovací režim**

   * Při vývoji lze využít emulátor Trezoru (`trezor-user-env`).
   * CI pipeline tak dokáže otestovat celé signovací flow i bez fyzického HW.

---

#### Diskuze (proč takto / alternativy)

* **HWI** – poskytuje jednotné CLI pro různé HW peněženky, aktivně vyvíjeno v rámci Bitcoin Core, minimalizuje nutnost psát vlastní protokol.

  * *Alternativy*: přímá implementace HID+protobuf pro Trezor (více flexibility, ale náročná na údržbu), Trezor Connect (JS, vhodné pro web, ne pro Kotlin backend).

* **Izolace služby** – běh v odděleném kontejneru/VM zajišťuje, že případný exploit v HWI neohrozí celý backend.

  * *Alternativy*: integrovat HWI přímo do cloud-backendu (méně overheadu, ale vyšší riziko).

* **Auditovatelnost** – každé volání je logováno bez citlivých dat, což zlepšuje možnost detekovat anomálie.

  * *Alternativy*: bez audit logů → složitější reakce na incidenty.

* **Testovací emulátor** – použití trezor-user-env umožňuje realistické testování bez HW.

  * *Alternativy*: jednoduché mocky → méně přesné, nezachytí všechny chyby.

---


#### Shrnutí

Signer Service je úzký, izolovaný modul, který má jediný účel: **spolehlivě a bezpečně zajistit komunikaci s HW peněženkou**. Díky HWI podporuje více zařízení, je snadno auditovatelný a dá se bezpečně testovat v CI prostředí s emulátorem. Izolace minimalizuje dopady chyb nebo exploitů.

### 5.2.6 Databáze (`DB`)

#### Úloha komponenty v systému

Databáze je centrální úložiště metadat celé aplikace. Běží nad **PostgreSQL** a ukládá pouze **watch-only informace**, stav transakcí a auditní záznamy. Nikdy neobsahuje privátní klíče ani seedu, všechny citlivé operace probíhají pouze na HW peněžence.

Hlavní odpovědnosti:

* **Watch-only deskriptory a účty** – evidence importovaných peněženek (single-sig i multisig), jejich deskriptorů a xpubů.
* **Multisig konfigurace** – uložení parametrů M z N, fingerprintů signatářů a mapování na uživatelské účty.
* **UTXO cache** – lokální zrcadlo dostupných vstupů pro rychlou odezvu.
* **Historie transakcí** – seznam transakcí, jejich stav (pending, částečně podepsaná, finalizovaná, broadcastovaná).
* **Audit log podpisů** – záznam každého volání Signer Service: kdo, kdy a s jakým výsledkem podepisoval.
* **Stav transakcí v multisigu** – ukládá, kolik podpisů už bylo přidáno, kdo je poskytl a kolik ještě chybí.

---

#### Vnitřní členění a workflow

1. **Accounts/Descriptors**

   * Tabulka `accounts`: id, typ (single/multisig), descriptor, fingerprint.
   * Tabulka `signers`: seznam známých signatářů s fingerprintem a xpubem.

2. **Multisig Wallets**

   * Tabulka `multisig_config`: M, N, reference na signers.
   * Tabulka `multisig_state`: pending transakce, počet přidaných podpisů, seznam signatářů, kteří podepsali.

3. **UTXO a historie**

   * Tabulka `utxo`: txid, vout, value, scriptPubKey, status (unspent/spent), lastSeen.
   * Tabulka `transactions`: psbt/raw, stav (draft/signed/broadcast), txid, čas vytvoření.

4. **Audit**

   * Tabulka `audit_logs`: čas, user/session id, akce (např. `signpsbt`, `getaddress`), výsledek.

---

#### Bezpečnostní opatření

* V databázi nejsou nikdy uloženy privátní klíče ani seedu.
* U multisigu jsou uloženy pouze veřejné informace (xpuby, fingerprinty, partial signatures), nikoliv klíče k podpisu.
* Auditní logy nesmí obsahovat citlivá data (např. PSBT v raw podobě), ale jen metadata.
* Přístup k DB je omezen na backend služby, oddělený účet s minimálními právy.
* Pravidelné zálohy + test obnovy.

---

#### Diskuze (proč takto / alternativy)

* **Postgres** – stabilní, ověřený systém s dobrou podporou pro transakce, integritní omezení a JSON datové typy.

  * *Alternativy*:

    * **SQLite** – jednodušší, vhodné pro single-node, ale nevhodné pro cloudové nasazení a škálování.
    * **NoSQL (Mongo, Cassandra, …)** – nepotřebujeme, protože data mají jasně relační strukturu (UTXO, transakce, signers).

* **Multisig stav v DB** – nutný, protože blockchain sám o sobě neuchovává partial signatures. Bez DB by museli uživatelé přenášet podpisy manuálně (např. QR kódy).

  * *Alternativy*: externí koordinátor (např. Sparrow, Specter), ale pro naši architekturu chceme integrované řešení.

* **Audit logy** – umožňují dohledat, kdo a kdy podepisoval. To je důležité zejména v multisig scénářích.

  * *Alternativy*: žádné logy → složitější řešení incidentů.

---

#### Shrnutí

Databáze je centrální zdroj pravdy pro stav peněženek a transakcí. Ukládá pouze watch-only informace, UTXO, historii a stav podpisů u multisigu. Díky tomu aplikace může zobrazovat aktuální stav („2/3 podepsáno“) bez nutnosti manuální koordinace uživatelem. Privátní klíče zůstávají vždy jen v HW peněžence.

