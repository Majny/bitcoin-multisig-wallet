# Přehled Use Cases

Tato sekce popisuje jednotlivé **Use Cases (UC)** mobilní aplikace pro správu Bitcoin prostředků. Každý UC specifikuje interakci uživatele s aplikací a je podrobně rozpracován v samostatném souboru.

Use Cases navazují na [User Stories](../02-user-stories/us-overview.md).

---

## Základní práce s peněženkou

- [UC-01: Připojení Trezoru](./UC-01-connect-trezor)
- [UC-08: Výběr účtu z Trezoru](./UC-08-select-account)
- [UC-02: Zobrazení zůstatku](./UC-02-view-balance)
- [UC-03: Zobrazení historie transakcí](./UC-03-view-transaction-history)
- [UC-04: Přijetí BTC](./UC-04-recieve-btc)

---

## Vytváření a správa transakcí

- [UC-05: Odeslání BTC](./UC-05-send-btc)
- [UC-06: Coin control – manuální výběr UTXOs](./UC-06-coin-control)
- [UC-07: Řazení UTXOs](./UC-07-sort-utxos)

---

## Multisig PSBT workflow

- [UC-09: Výběr multisig peněženky](./UC-09-select-multisig-wallet)
- [UC-10: Přehled PSBT transakcí](./UC-10-psbt-overview.md)
- [UC-11: Detail PSBT transakce](./UC-11-psbt-detail)
- [UC-12: Podepsání PSBT pomocí Trezoru](./UC-12-sign-psbt)
- [UC-13: Odeslání multisig transakce (Broadcast)](./UC-13-broadcast-psbt)
- [UC-14: Import multisig peněženky pomocí descriptoru](./UC-14-import-multisig-wallet)

---

## Diagram Use Casů

Pro vizuální přehled všech UC přejděte na:  
📄 [`diagrams/UC/overview.puml`](../../diagrams/UC/overview.puml)
