# Kapitoly 1 a 2
## Pripominky

## strana 8:
**zaklady** - nejspis moc strucne, mozna zmenit nazev na "Prehled protokolu Bitcoin"
**koncepty** - asi byt vice konkretni, takze co popisuju - struktura transakci, system adres a mechanismus spravy klicu

## strana 9:
**singlesig** - musim definovat (pridat do nejakych pojmu?)
**multisignitare** - -||-
**nasi aplikaci** - neformalni, misto toho neco jako aplikace popsasna v teto praci
**redeem skript** - same
**M-of-N** - same
**serveru** - napsat proste vic obecne, ne konkretne
**adresy penezenky** - matouci pojem spis "adresy odvozene z verejnych klicu"
**nasi aplikaci** - klasika
**PBKDF2** - dat do slovniku? Bude to stacit ten slovnik? **zeptat se**
**passphrase** - anglicky a nevysvetleny .... mozna obe tyhle veci odstranit nebo vice popsat proste heslo + salt -> 2048 kol sha512 -> seed, mozna nechat protoze trezor to resi? **zeptat se**

## strana 10:
**velka veta** - malo vysvetlene nejspis - hlavne odstranit tu implementaci, ta mi tam zustala a dat do jiny kapitoly do kapitoly 4 jako technologickou vyzvu **TODO**
**cosigneru** - nevysvetleno..
**PSBT (BIP-174)** - tady si nejsem jistej, mby proste popsat predtim? **zeptat se**
**zivotni cyklus** - zni divne a hlavne se naucit pocitat **TODO**, lepsi "definuje sest roli pri zpracovani PSBT"

## Strana 11:
**velka veta** - dat uplne pryc, do kapitoly 3
**Sparrow Walletu** - preklep, zeptat se jestli tam ten odstavec patri nebo tam nemam davat veci z praxe **zeptat se**

## Strana 12:
**vzorec** - po overeni to tam je uplne spatne, nepocital jsem to ve vbytech BIP141 
weight = base_size * 3 + total_size
vsize = weight / 4
base_size = 41B
total_size = 41 + witness_bytes (cela transakce = non_witness + witness) ... takze vsize = 41 + witness_bytes / 4 
witness_bytes = 6B witness data +  73 * M podpisu .. 2* 32B cislo + hlavicka .. max 72B + 34 * N .. 1B opcode + 33B pubkey .... Pro 2-of-3: (170+146+102)/4 = 104,5 vB ... domluvit se jestli to tam vubec psat, aby mi to pak nezkomplikovalo obhajobu **zeptat se**
**Hardwarove penezenky** - to si nejsem jisty, mozna vyjmenovat vic? **zeptat se**

## Strana 13:
**Komunikace s Trezorem** - obecneji .. s hardwarovou penezenkou
**USB-C** - popis technologie a ten tam nepatri?

## Strana 15:
**dostupny** - koncovka..

## Strana 18:
**chci importovat** - nevim, mozna ze qr kod i? **zeptat se**
**vychazeji pripady uziti** - chybi mapovani? nebo mozna jina formulace 

## Straan 19:
**deseti** - vysvetlit proc 10, jak?
**singlesig** - nevysvetleny pojem
**fiat mene** - -||-

## Strana 20:
**trezor suite mobile** - na mobilu nejde komunikovat s trezorem primo, musim pres trezor connect API
**Sparrow Wallet** - use case nejspis nema odkazovat na konkretni nastroje?
**zkopirovanim textu** - zatim jen pouze kdybych nestihl qr kod, pak se upravi

## Strana 21:
**ucet na druheho cosignera** - moc specificky

## Strana 24:
**deplink mechanismu** - definovat co je deeplink? **zeptat se**

## Strana 25:
**JWT tokeny** - zase nekde definovat
**trezor suite mobile** - zduraznit proc to neslo jinak nekde
**USB-C** - zas popis technologie? nebo napsat obecneji prostrednictvim USB






# Poznamky z konzultace

