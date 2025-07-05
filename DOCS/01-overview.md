# Přehled aplikace

Tato mobilní aplikace umožňuje uživatelům bezpečně spravovat Bitcoin prostředky prostřednictvím hardware peněženkz ve spojení se vzdáleným Bitcoin node provozovaným správcem služby.

Aplikace poskytuje přehled Bitcoin zůstatku, umožňuje sledovat UTXOs, manuálně vybírat UTXOs pro transakce pomocí coin control a vytvářet transakce, včetně transakcí s více pospisy (multisig).

Aplikace rozlišuje dvě hlavní role:

- **Uživatelé**, kteří spravují své Bitcoin prostředky pomocí Trezoru a využívají vzdálený node pro práce s blockchainem.
- **Správce**, kteý provozuje backend server s Bitcoin node a zajišťuje přístup uživatelům aplikace.

---

## Uživatelské role

### Uživatelé
- Připojení Trezor hardware peněženky.
- Výběr účtu odvozených z hardware peněženky.
- Zobrazení Bitcoin zůstatku a historie transakcí.
- Manuální výběr UTXOs pro transakce pomocí coin cointrol.
- Vytváření a podepisování Bitcoin transakcí, včetně multisig transakcí.
- Sledování stavu transakcí a potvrzení.
- Nastavení peněženky, preerencí poplatků a bezpečnostních možností.

### Návrhová filozofie
- Mobilní aplikace optimalizovaná pro Bitcoin uživatele využívající hardware peněženku.
- Důraz na bezpečnost, privátní klíče zůstávají v peněžence.
- Jednoduchá navigace přizpůsobená uživatelským potřebám:
    - Pro uživatele: 'Home, Send BTC, Recieve BTC, Settings'
- Navržené pro pokročilé uživatele požadující soukromí a plnou kontrolu nad transakcemi.
