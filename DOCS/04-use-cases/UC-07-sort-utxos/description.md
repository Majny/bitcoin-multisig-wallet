
# UC-07: Řazení UTXOs

## Popis
Uživatel si může seřadit seznam UTXOs podle různých kritérií, aby měl lepší přehled při ručním výběru vstupů (Coin Control).

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet.
- Uživatel má v aplikaci deaktivovaný režim "Auto Select“ UTXOs a je v "Edit Selection".
- Backend server je dostupný.
- Uživatel má k dispozici alespoň jeden UTXO.

## Main Flow
1. Uživatel se nachází v režimu Coin Control (viz UC-06).
2. Aplikace zobrazí seznam dostupných UTXOs.
3. Uživatel si zvolí kritérium řazení.
4. Aplikace přepočítá a zobrazí seznam UTXOs podle zvoleného kritéria.
5. Uživatel může změnit kritérium řazení kdykoliv, než potvrdí výběr UTXOs.

---

## Alternative Flow
3A. Uživatel nezvolí žádné kritérium řazení.

## Postconditions
- Uživatel má seřazený seznam UTXOs dle zvoleného kritéria a může pokračovat ve výběru vstupů v Coin Control (UC-06).
