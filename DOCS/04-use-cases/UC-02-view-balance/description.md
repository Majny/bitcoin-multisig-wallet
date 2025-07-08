# UC-02: Zobrazení zůstatku

## Popis
Uživatel si zobrazí aktuální zůstatek Bitcoin peněženky na vybraném účtu.

## Actors
- Uživatel

## Preconditions
- Uživatel má úspěšně připojený Trezor a vybraný účet / peněženku.
- Backend server je dostupný a správně synchronizovaný s Bitcoin sítí.

## Main Flow
1. Uživatel otevře hlavní dashboard aplikace.
2. Aplikace zobrazí zůstatek Bitcoin peněženky na vybraném účtu.
3. Aplikace automaticky aktualizuje zůstatek podle definovaného intervalu.
4. Zůstatek se zobrazí i ve zvolení měně.

---

## Alternative Flow
2A. Zůstatek se nepodaří načíst kvůli chybě komunikace s backendem, aplikace zobrazí "Unknown" a nabídne možnost "Refresh".

## Postconditions
- Uživatel vidí aktuální zůstatek své Bitcoin peněženky v BTCa zvolené měně.
- Aplikace pravidelně aktualizuje zůstatek.


