# UC-13: Odeslání podepsané multisig transakce

## Popis
Uživatel odešle plně podepsanou PSBT transakci do Bitcoin sítě.

## Actors
- Uživatel

## Preconditions
- Uživatel má vybranou multisig peněženku (viz. UC-O9).
- Transakce má dostatek podpisů pro validní odeslání.
- Uživatel otevřel detail této PSBT transakce (viz. UC-11).

## Main Flow
1. Aplikace rozpozná, že transakce je kompletně podepsaná.
2. Zobrazí se tlačítko "Broadcast“.
3. Uživatel klikne na "Broadcast“.
4. Aplikace odešle transakci do Bitcoin sítě.
5. Zobrazí se potvrzení o úspěšném odeslání.
6. Transakce zmizí ze seznamu PSBTs a objeví se v historii transakcí.

---

## Alternative Flow
4A. Transakce selže při odeslání.

---

## Postconditions
- Transakce je úspěšně odeslána do Bitcoin sítě.
- Stav transakce je aktualizován.
- Transakce je zařazena do historie.
