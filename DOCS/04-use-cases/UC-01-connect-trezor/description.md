# UC-01: Připojení Trezoru

## Popis
Uživatel připojí svůj Trezor, aby mohl zobrazit zůstatek, histrii a spravovat peněženku.

## Actors
- Uživatel

## Preconditions
- Uživatel má funkční trezor s aktivní peněženkou.
- Trezor je fyzicky dostupný a připravený ke spojení.

## Main Flow
1. Uživatel otevře aplikaci.
2. Aplikace zobrazí úvodní obrazovku "No Wallet Connected" s výzvou "Connect Trezor".
3. Uživatel klikne na tlačítko "Connect Trezor".
4. Aplikace zahájí spojení se zarížením Trezor.
5. Zobrazí se obrazovka "Connecting to Trezor...".
6. Uživatel musí na Trezoru potvrdit připojení / zadat PIN.
7. Po úspěšném spojení se zobrazí seznam dostupných účtů / pěněženek.
7. Uživatel si vybere účet, který chce načíst.
8. Jakmile je účet načtený, aplikace zobrazí „Trezor Connected“ s tlačítkem „Continue“.
9. Uživatel klikne na "Continue" a je přesměrován na hlavní dashboard. 

---

## Alternative Flow
4A. Trezor se nepodaří připojit nebo rozpoznat, uživatel může ukončit spojení pomocí "Cancel" a opakovt hledání pomocí "Retry".
5A. Uživatel odmítne připojení nebo zadá špatný PIN, tím se ukončí spojení a vrátí se na obrazovku "No Wallet Connected".

## Postconditions
- Trezor je úspěšně připojen.
- Uživatel si zvolil účet / peněženku, kterou chce spravovat.- Aplikace načetla odpovídající zůstatek a transakce.
- Uživatel může pracovat s peněženkou na hlavním dashboardu.
