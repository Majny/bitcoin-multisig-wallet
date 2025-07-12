# UC-10: Přehled PSBT transakcí

## Popis
Uživatel si prohlíží přehled všech rozpracovaných PSBT transakcí v rámci vybrané multisig peněženky.

## Actors
- Uživatel

## Preconditions
- Uživatel má vybranou multisig peněženku (viz UC-09).
- Vybraná peněženka obsahuje alespoň jednu PSBT transakci.

## Main Flow
1. Aplikace načte a zobrazí seznam všech PSBT transakcí pro vybranou multisig peněženku.
2. Uživatel vidí u každé PSBT transakce:
   - Datum vytvoření.
   - Částku transakce.
   - Počet požadovaných podpisů (např. "Waiting for 2 signatures").
3. Uživatel může:
   - Otevřít detail PSBT transakce.
   - Vytvořit novou PSBT transakci.
   - Importovat PSBT transakci pomocí QR kódu nebo textového vstupu.

---

## Alternative Flow
1A. Pokud vybraná multisig peněženka nemá žádné PSBT transakce, tak aplikace zobrazí informaci „No PSBTs available“ a nabídne možnost vytvořit nebo importovat novou PSBT.

---

## Postconditions
- Uživatel vidí přehled všech PSBT transakcí pro vybranou multisig peněženku a může provádět další akce.
