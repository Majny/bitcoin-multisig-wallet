##trezor -> backend -> trezor
* přes prohlížeč, trezor connect API .. bude to Chrome Custom Tab
* Android US Host API

## Android US Host API
HID -> Wire/Protocol -> High-level client

1. Protobuf definice zpravy

* V blockchainu jen finalni broadcastove transakce, nepoznam tam kolik sigu je v multisigu  

# NODE BUILD
Pripojit SSD -> stahnout potrebne balicky -> clone bitcoin repa z githubu -> nejnovejsi verze -> overeni podpisu Gloria Zhao
Jdu dat ext4 na SSD

cmake -S . -B build -G Ninja \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DBUILD_GUI=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_BENCH=OFF \
  -DENABLE_WALLET=ON \
  -DWITH_ZMQ=ON \
  -DENABLE_IPC=OFF
  
  
majny@rpi:~/src/bitcoin$ cmake --build build --parallel
[0/317] Building CXX object src/CMakeFiles/leveldb.dir/leveldb/db/db_impl.cc.o

Naistalovano do systemu pomoci: sudo cmake --install build


rpcauth: bitcoin/share/rpcauth/rpcauth.py

rpcauth=backend:e75aa935e906df42dd6d389985e6d96c$326ac1efe0dc96446cffda1b3786b0e6083a9a2f60f207cecfcb0291376cab39
Your password:
mqn47rm-NpYQTpJjyD6k3A1xU6ziuKbPTLfYlu9JSok


[majny@archbook ~]$ bitcoin-cli -rpcconnect=100.79.139.14 -rpcuser=backend -stdinrpcpass getblockchaininfo <<< 'mqn47rm-NpYQTpJjyD6k3A1xU6ziuKbPTLfYlu9JSok'
bash: bitcoin-cli: command not found
[majny@archbook ~]$ curl -u backend:mqn47rm-NpYQTpJjyD6k3A1xU6ziuKbPTLfYlu9JSok \
  -H 'content-type: text/plain' \
  --data-binary '{"jsonrpc":"1.0","id":"x","method":"getblockchaininfo","params":[]}' \
  http://100.79.139.14:8332/
{"result":{"chain":"main","blocks":211769,"headers":918551,"bestblockhash":"0000000000000276c0819ccef2984671034ee43e28e09a9d84f6f4639c6a52d1","bits":"1a04fa62","target":"00000000000004fa620000000000000000000000000000000000000000000000","difficulty":3370181.799277837,"time":1355220405,"mediantime":1355217946,"verificationprogress":0.007778188648861062,"initialblockdownload":true,"chainwork":"00000000000000000000000000000000000000000000002382dceacc6a6f5c86","size_on_disk":562788533299,"pruned":false,"warnings":[]},"error":null,"id":"x"}
[majny@archbook ~]$ 


AKTUALNI: import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import java.util.Base64

private val client = HttpClient(CIO) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
}

suspend fun callRpc(method: String, params: List<Any?> = emptyList()): String {
    val url  = System.getenv("RPC_URL")  ?: "http://100.79.139.14:8332/"
    val user = System.getenv("RPC_USER") ?: "backend"
    val pass = System.getenv("RPC_PASS") ?: error("RPC_PASS missing")
    val auth = "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
    val body = """{"jsonrpc":"1.0","id":"x","method":"$method","params":${params}}"""
    return client.post(url) {
        header(HttpHeaders.Authorization, auth)
        header(HttpHeaders.ContentType, "text/plain")
        setBody(body)
    }.bodyAsText()
}




RPC_URL	http://100.79.139.14:8332/	URL, kam se aplikace připojuje přes Tailscale
RPC_USER	backend	uživatelské jméno (z rpcauth=backend:...)
RPC_PASS	mqn47rm-NpYQTpJjyD6k3A1xU6ziuKbPTLfYlu9JSok

# Cloud Stuff





















import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.net.*

fun rpcClient(): HttpClient = HttpClient(CIO) {
    engine {
        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
        requestTimeout = 60_000
    }
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
}

suspend fun callRpc(method: String, params: List<Any?> = emptyList()): String {
    val url  = System.getenv("RPC_URL")    // "http://<onion>:8332/"
    val user = System.getenv("RPC_USER")   // "backend"
    val pass = System.getenv("RPC_PASS")   // heslo z rpcauth.py

    val body = """{"jsonrpc":"1.0","id":"x","method":"$method","params":${params}}"""

    val client = rpcClient()
    return client.post(url) {
        header(HttpHeaders.ContentType, "text/plain")
        header(HttpHeaders.Authorization, Credentials.basic(user, pass))
        setBody(body)
    }.bodyAsText()
}

RPC_ONION=242dzvk3pnxsdho5fkomnjsvndxlbvhlimsmzqpjuwfpgnvn7mqexyid.onion


export RPC_USER=backend
export RPC_PASS='…sem dej heslo…'
export RPC_ONION='xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.onion'

curl --socks5-hostname 127.0.0.1:9050 \
  -u "$RPC_USER:$RPC_PASS" \
  -H 'content-type: text/plain' \
  --data-binary '{"jsonrpc":"1.0","id":"x","method":"getblockchaininfo","params":[]}' \
  "http://${RPC_ONION}:8332/"




[http] Received a POST request for / from 127.0.0.1:33190
[rpc] ThreadRPCServer method=getblockchaininfo user=backend



Takze v podstate bez jineho softwaru to nezjistim. Budu to teda muset udelat tak, ze v tom jednom uctu, ktery mam v celem trezoru v me aplikaci, ten bude tedy definovany nejakym fingerprintem a ulozen databazi. Tak v nem primo umet vytvorit dalsi penezenku, ktera nebude vazana na trezor, ale transakce tam budu moct podepisovat. Zaroven tam bude potreba pri vytvoreni definovat jine klice, ktere mohou podepsat dalsi casti a tam by teoreticky slo udelat to, ze kdyz se ten novy ucet prihlasi do me aplikace, tak tam automaticky bude videt, ze patri k tehle multisig penezence ne?

„Mít jeden ‘účet’ (device) a v něm vytvořit další multisig peněženku, kterou můžu podepisovat, a až se do app přihlásí jiný cosigner, automaticky uvidí, že je v té samé multisig peněžence.“

Tohle jde – přes Wallet Registry + deterministické identifikátory.


Antonopoulos o PSBT: 
The only other widely used transaction serialization format that we’re aware of is the
partially signed bitcoin transaction (PSBT) format documented in BIPs 174 and 370
(with extensions documented in other BIPs). PSBT allows an untrusted program to
produce a transaction template that can be verified and updated by trusted programs
(such as hardware signing devices) that have the necessary private keys or other
sensitive data to fill in the template. To accomplish this, PSBT allows storing a
significant amount of metadata about a transaction, making it much less compact than
the standard serialization format. This book does not go into detail about PSBT, but we
strongly recommend it to developers of wallets that plan to support signing with
multiple keys.