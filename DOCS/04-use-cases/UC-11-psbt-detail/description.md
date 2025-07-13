# UC-11: Detail PSBT transakce

## Popis
Uživatel si zobrazí detail vybrané PSBT transakce a rozhodne se, zda ji podepíše, exportuje, nebo pouze zkontroluje stav.

## Actors
- Uživatel

## Preconditions
- Uživatel má vybranou multisig peněženku (viz UC-09).
- Uživatel se přesunul do přehledu PSBT transakcí (viz UC-10).

## Main Flow
1. Uživatel si vybere konktrétní PSBT.
2. Aplikace zobrazí detailní informace o vybrané PSBT:
   - Vstupy a výstupy (včetně adres a částek).
   - Počet potřebných a aktuálních podpisů.
   - Transakční poplatek.
3. Uživatel má k dispozici následující možnosti:
   - Podepsat transakci pomocí Trezoru (viz. UC-11).
   - Exportovat PSBT jako QR nebo text.
   - Zpět na seznam PSBT (viz. UC-10).

---

## Alternative Flow
3A. Pokud uživatel zvolí podpis, ale transakce je již kompletně podepsaná, tak aplikace nabídne možnost broadcastu (viz. UC-13).

---

## Postconditions
- Uživatel úspěšně zobrazil detail transakce a mohl s ní pracovat podle svého rozhodnutí.
