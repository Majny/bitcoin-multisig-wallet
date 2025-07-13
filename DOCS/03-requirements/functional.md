# Funkční Požadavky

## Trezor a peněženka
- Aplikace umožní připojení Trezoru a výběr účtu.
- Aplikace zobrazí zůstatek a historii transakcí pro daný účet.

## Transakce
- Uživatel může odesílat BTC prostředky.
- Uživatel může ručně vybrat UTXOs (coin control).
- Aplikace umožní zobrazit detail transakce.

## Multisig
- Uživatel může importovat multisig peněženku pomocí descriptoru.
- Aplikace zobrazí seznam rozpracovaných PSBT transakcí.
- Uživatel může vytvořit novou PSBT transakci, podepsat ji pomocí Trezoru a následně ji odeslat do sítě.

## PSBT práce
- Aplikace podporuje export a import PSBT transakcí (QR, text).
- Aplikace aktualizuje stav podpisů a rozpozná, kdy je transakce připravená k odeslání.

(Detailní scénáře viz [Use Cases](../04-use-cases/).)
