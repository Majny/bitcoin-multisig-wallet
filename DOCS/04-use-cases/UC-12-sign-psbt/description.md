# UC-12: Podepsání PSBT transakce pomocí Trezoru

## Popis
Uživatel podepíše vybranou PSBT transakci pomocí svého Trezoru, čímž přidá svůj podpis.

## Actors
- Uživatel

## Preconditions
- Uživatel má vybranou multisig peněženku (viz UC-09).
- Uživatel má otevřený detail konkrétní PSBT transakce (viz UC-11).
- Transakce ještě není plně podepsaná.

## Main Flow
1. Uživatel klikne na tlačítko "Sign PSBT“.
2. Aplikace odešle požadavek na zařízení Trezor.
3. Trezor zobrazí detaily transakce.
4. Uživatel potvrzuje podpis na zařízení.
5. Po potvrzení Trezor vygeneruje podpis.
6. Aplikace přidá podpis k PSBT a aktualizuje stav transakce.
7. Pokud je dosažen potřebný počet podpisů, transakce se označí jako "Broadcast“.

---

## Alternative Flow
3A. Uživatel odmítne podepsat.
4A. Trezor není dostupný nebo nastane chyba během komunikace, pak aplikace zobrazí chybovou hlášku a nabídne možnost "Retry“ nebo "Cancel“.

---

## Postconditions
- Transakce obsahuje nový podpis.
- Pokud byly přidány poslední chybějící podpisy, PSBT je připraven k odeslání do sítě.
