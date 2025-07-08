# UC-04: Přijetí BTC

## Popis
Uživatel si vygeneruje Bitcoin adresu pro příjem prostředk na svůj účet.

## Actors
- Uživatel

## Preconditions
- Uživatel má připojený Trezor a vybraný účet.
- Backend server je dostupný.

## Main Flow
1. Uživatel na hlavním dashboard klikne na tlačítko "Recieve".
2. Aplikace zobrazí obrazovku pro příjem Bitcoinů s nově vygenerovanou přijímací adresou.
3. Uživatel může zkopírovat adresu nebo vyfotit QR kód.
4. Po příjmu prostředků se transakce automaticky zobrazí v historii transakce.

---

## Alternative Flow
2A. Adresu se nepodaří vygenerovat kvůli chybě.

## Postconditions
- Uživatel má k discpozici platnou Bitcoin adresu pro příjem prostředků.
- Aplikace je připravena transakci zaznamenat po jejím potvrzení.
