# UC-14: Import multisig peněženky

## Popis
Uživatel importuje existující multisig peněženku pomocí descriptoru, aby mohl spravovat transakce v této peněžence.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vbraný účet (viz. UC-08).
- Uživatel má připravený descriptor (nebo soubor s konfigurací multisig peněženky).

## Main Flow
1. Uživatel přejde z hlavního menu na obrazovku "Multisig Wallets“.
2. Uživatel přejde na "Import Wallet".
3. Uživatel vloží descriptor (nebo nahraje soubor).
4. Aplikace ověří validitu descriptoru.
5. Pokud je descriptor validní, aplikace zobrazí název peněženky a základní parametry.
6. Uživatel potvrdí import.
7. Peněženka se objeví v seznamu dostupných multisig peněženek.

---

## Alternative Flow
3A. Pokud descriptor není validní, tak aplikace zobrazí chybové hlášení.

---

## Postconditions
- Multisig peněženka je úspěšně importována a připravena k použití.
