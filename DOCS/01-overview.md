# Přehled aplikace

Tato mobilní aplikace umožňuje pokročilým Bitcoin uživatelům bezpečně spravovat své prostředky pomocí hardwarové peněženky **Trezor**. Aplikace podporuje standardní i **multisig peněženky**, umožňuje **manuální výběr UTXO (coin control)** a kompletní práci s **PSBT transakcemi** v rámci multisig setupu.

## Hlavní funkce aplikace 

### 

Aplikace rozlišuje dvě hlavní role:

- **Uživatelé**, kteří spravují své Bitcoin prostředky pomocí Trezoru a využívají vzdálený node pro práce s blockchainem.

---

## Uživatelské role

### Uživatelé
- Připojení Trezor hardware peněženky.
- Výběr účtu odvozených z hardware peněženky.
- Zobrazení Bitcoin zůstatku a historie transakcí.
- Manuální výběr UTXOs pro transakce pomocí coin cointrol.
- Vytváření a podepisování Bitcoin transakcí, včetně multisig transakcí.
- Sledování stavu transakcí a potvrzení.
- Nastavení peněženky, preerencí poplatků a bezpečnostních možností.

---

## Multisig peněženky

Aplikace umožňuje import a správu multisig peněženek podle M-of-N schématu:

- Import pomocí descriptoru nebo konfiguračního souboru.
- Sledování a podepisování PSBT transakcí.
- Možnost exportu a broadcastu transakcí po dosažení dostatečného počtu podpisů.

Každá multisig peněženka je spravována odděleně a má vlastní seznam transakcí.

---

## Požadavky

Detailní funkční a nefunkční požadavky jsou popsány v samostatné sekci:

[Požadavky na systém](./03-requirements/requirements-overview.md)

Z nich vycházejí jednotlivé User Stories a Use Casy.


---

## User Stories

Detailní popis funkcionality je specifikován pomocí User Stories:

[User Stories](./02-user-stories/us-overview.md)

Z těchto scénářů vycházejí konkrétní Use Casy a jejich implementace.

---

## Use Case diagram

Pro vizuální přehled všech klíčových scénářů doporučuji nahlédnout do Use Case diagramu:

[Use Case Diagram](./diagrams/UC/uc-overview.svg)

---

### Návrhová filozofie
- Mobilní aplikace optimalizovaná pro Bitcoin uživatele využívající hardware peněženku.
- Důraz na bezpečnost, privátní klíče zůstávají v peněžence.
- Jednoduchá navigace přizpůsobená uživatelským potřebám:
    - Pro uživatele: 'Home, Send BTC, Recieve BTC, Settings'
- Navržené pro pokročilé uživatele požadující soukromí a plnou kontrolu nad transakcemi.
