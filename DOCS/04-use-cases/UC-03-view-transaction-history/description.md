UC-03: Zobrazení historie a transakcí

## Popis
Uživatel si zobrazí historii transakcí své Bitcoin peněženky na vybraném účtu.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet (viz. UC-08).

## Main Flow
1. Uživatel otevře hlavní dashboard aplikace.
2. Aplikace zobrazí seznam transakcí pro vybraný účet.
3. Seznam obsahuje základní informace o transakcích.

---

## Alternative Flow
2A. Seznam transakcí se nepodaří načíst kvůli chybě komunikace, aplikace zobrazí hlášení o chybě a nabídne možnost "Retry".

## Postconditions
- Uživatel má přehled o všech transakcích své Bitcoin peněženky.
