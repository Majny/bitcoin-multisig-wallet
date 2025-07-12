# UC-09: Výběr multisig peněženky

## Popis
Uživatel si vybírá konkrétní multisig peněženku, se kterou chce pracovat.

## Actors
- Uživatel

## Preconditionsi
- Uživatel má připojený Trezor k aplikaci a má vybraný účet.
- Uživatel má v aplikaci alespoň jednu multisig peněženku (importovanou nebo dříve vytvořenou).

## Main Flow
1. Uživatel otevře sekci „Multisig Wallets“ v hlavním menu aplikace.
2. Aplikace zobrazí seznam všech multisig peněženek.
3. Uživatel vidí u každé peněženky:
   - Název peněženky
   - Počet požadovaných podpisů (např. „2 of 3 required“)
   - Počet bitcoinů, který je v peněžence
4. Uživatel vybere požadovanou multisig peněženku kliknutím na ni.

---

## Alternative Flow
2A. Pokud uživatel nemá žádnou multisig peněženku, tak aplikace zobrazí informaci „No multisig wallets available“ a nabídne možnost importu nové peněženky.

---

## Postconditions
- Uživatel má vybranou multisig peněženku.
