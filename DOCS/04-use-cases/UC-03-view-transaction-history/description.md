UC-03: Zobrazení historie a transakcí

## Popis
Uživatel si zobrazí historii transakcí své Bitcoin peněženky na vybraném účtu.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet.
- Backend server je dostupný a synchronizovaný.

## Main Flow
1. Uživatel otevře hlavní dashboard aplikace.
2. Aplikace zobrazí seznam transakcí pro vybraný účet.
3. Seznam obsahuje základní informace o transakcích.
4. Uživatel může kliknout na konkrétní transakci a zobrazit detailní informace.

---

## Alternative Flow
2A. Seznam transakcí se nepodaří načíst kvůli chybě komunikace, aplikace zobrazí hlášení o chybě a nabídne možnost "Retry".

## Postconditions
- Uživatel má přehled o všech transakcích své Bitcoin peněženky.
