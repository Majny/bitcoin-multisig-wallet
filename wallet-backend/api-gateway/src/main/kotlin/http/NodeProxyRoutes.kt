package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.BroadcastTxRequest
import cz.majny.wallet.gateway.dto.JsonRpcRequest
import cz.majny.wallet.gateway.dto.TestTxRequest
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.nodeProxyRoutes() {
    authenticate("auth-jwt") {

        route("/node") {

            /**
             * POST /api/v1/node/rpc
             * Generic JSON-RPC passthrough to Bitcoin Core (via Node Proxy).
             * Only allowlisted methods are permitted.
             */
            post("/rpc") {
                val req = call.receive<JsonRpcRequest>()
                val result = call.application.deps.nodeProxy.rpcCall(req)
                call.respond(result)
            }

            /**
             * POST /api/v1/node/tx/test
             * Test if a raw transaction would be accepted by the mempool.
             * Body: { "hex": "<raw_tx_hex>" }
             */
            post("/tx/test") {
                val req = call.receive<TestTxRequest>()
                val result = call.application.deps.nodeProxy.testTransaction(req.hex)
                call.respond(result)
            }

            /**
             * POST /api/v1/node/tx/broadcast
             * Broadcast a signed raw transaction to the Bitcoin network.
             * Body: { "hex": "<raw_tx_hex>" }
             */
            post("/tx/broadcast") {
                val req = call.receive<BroadcastTxRequest>()
                val result = call.application.deps.nodeProxy.broadcastTransaction(req.hex)
                call.respond(result)
            }

            /**
             * GET /api/v1/node/health
             * Health check for Node Proxy connectivity.
             */
            get("/health") {
                val result = call.application.deps.nodeProxy.health()
                call.respond(result)
            }
        }
    }
}
