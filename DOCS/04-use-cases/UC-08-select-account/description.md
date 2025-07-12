# UC-08: Výběr účtu (Select Account)

## Popis
Uživatel si po připojení Trezoru vybírá konkrétní účet, který chce používat pro správu svých bitcoinových prostředků.

## Actors
- Uživatel

## Preconditions
- Uživatel úspěšně připojil své zařízení Trezor.
- Trezor poskytuje seznam dostupných účtů s jejich zůstatky.

## Main Flow
1. Aplikace zobrazí obrazovku s dostupnými účty načtenými z Trezoru.
2. Uživatel vidí seznam účtů s jejich zůstatky v BTC (například "Account #1", "Account #2" atd.).
3. Uživatel si vybere požadovaný účet.
4. Aplikace zobrazí tlačítko "Continue“ pro potvrzení výběru.
5. Uživatel klikne na "Continue“.
6. Aplikace načte vybraný účet a přesměruje uživatele na hlavní dashboard peněženky.

---

## Alternative Flow
4A. Uživatel zruší výběr pomocí "Cancel".

---

## Postconditions
- Uživatel má vybraný účet, se kterým bude pracovat v celé aplikaci.
- Aplikace načetla zůstatek a transakční historii pro vybraný účet.
