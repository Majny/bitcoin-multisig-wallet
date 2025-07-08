# UC-05: Odeslání BTC

## Popis
Uživatel vytvoří a odešle Bitcoin transakci na zvolenou cílovou adresu.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet.
- Backend server je dostupný.
- Uživatel má dostatek prostředků pro pokrytí částky a poplatků.

## Main Flow
1. Uživatel na hlavním dashboardu klikne na tlačítko "Send".
2. Aplikace zobrazí formulář pro odeslání BTC, kde uživatel zadá - cílovou Bitcoin adresu a částku v BTC.
3. Uživatel zvolí způsob výběru UTXOs:
    - Pokud je aktivní **Auto Select**:
        - Aplikace automaticky vybere UTXOs.
        - Uživatel nastaví poplatek výběrem možností "Low / Medium / High".
    - Pokud **Auto Select není aktivní**:
        - Aplikace přesměruje uživatele do **Coin Control** (viz UC-06), kde si ručně vybere UTXOs a nastaví přesný poplatek.
4. Uživatel potvrdí transakci.
5. Aplikace zobrazí souhrn transakce ke kontrole.
6. Uživatel potvrdí transakci na Trezoru.
7. Po úspěšném podepsání aplikace transakci odešle do Bitcoin sítě přes backend.
8. Aplikace zobrazí potvrzení o úspěšném odeslání a transakce se objeví v historii transakcí.

---

## Alternative Flow
2A. Uživatel zadá neplatnou adresu nebo částku.
6A. Uživatel odmítne transakci na Trezoru nebo nastane chyba při podepisování.

## Postconditions
- Transakce byla úspěšně podepsaná a odeslaná do Bitcoin sítě.
- Uživatel vidí transakci v historii transakcí.
