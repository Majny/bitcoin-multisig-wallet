;# UC-06: Coin Control

## Popis
Uživatel si ručně vybere konkrétní UTXOs, které budou použity při vytváření Bitcoin transakce.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet (viz. UC-08).
- Uživatel má v aplikaci deaktivovaný režim "Auto Select“ UTXOs.
- Uživatel má k dispozici alespoň jeden UTXO.

## Main Flow
1. Uživatel klikne na "Edit Selection".
2. Aplikace zobrazí seznam všech dostupných UTXOs pro vybraný účet, které si uživatel může seřadit podle různých kritérií (viz. UC-07).
3. Uživatel si zvolí konkrétní UTXOs, které chce použít pro aktuální transakci.
4. Aplikace zobrazí souhrn zvolených UTXOs s jejich částkami.
5. Uživatel nastaví přesnou výši poplatku.
6. Uživatel potvrdí výběr.
7. Aplikace vrátí vybrané UTXOs a nastavený poplatek zpět do procesu vytváření transakce (UC-05).

---

## Alternative Flow
2A. Uživatel nevybere žádné UTXO nebo výběr není dostatečný pro zadanou částk.

## Postconditions
- Uživatel úspěšně vybral UTXOs a nastavil poplatek pro aktuální transakci.
- Aplikace předala tyto údaje zpět do hlavního procesu odeslání transakce (UC-05).
