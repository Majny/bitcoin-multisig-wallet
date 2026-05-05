package cz.majny.wallet.blockchain.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * Tests for MempoolClientImpl.getChecked retry logic. The contract is:
 *   - Successful first response → return it, no retry.
 *   - 429 Too Many Requests → wait 1 s, retry once, give up after that.
 *   - HttpRequestTimeout / ConnectTimeout / SocketTimeout → wait 1 s, retry once.
 *   - IOException (connection reset etc.) → wait 1 s, retry once.
 *   - Non-success after retry → throw RuntimeException with diagnostic.
 *
 * Real network is never touched: every test installs a MockEngine that
 * returns canned bytes from in-memory bookkeeping. No mempool.space
 * traffic, no rate-limit consumption.
 *
 * Memory bug regressions guarded here: bug #17 (CIO connection-reset retry
 * after switch to OkHttp engine) and bug #20 (catch widened to IOException,
 * not only HttpRequestTimeoutException).
 */
class MempoolClientTest {

    private val addressInfoJson = """
        {
          "address": "tb1qtest",
          "chain_stats": { "funded_txo_count": 1, "funded_txo_sum": 100000,
                           "spent_txo_count": 0, "spent_txo_sum": 0,
                           "tx_count": 1 },
          "mempool_stats": { "funded_txo_count": 0, "funded_txo_sum": 0,
                             "spent_txo_count": 0, "spent_txo_sum": 0,
                             "tx_count": 0 }
        }
    """.trimIndent()

    /*
     * Builds a MempoolClientImpl whose HttpClient is wired to the supplied
     * MockEngine handler. The handler decides what each request returns.
     */
    private fun client(engine: MockEngine): MempoolClientImpl {
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        return MempoolClientImpl(
            baseUrl = "http://mock",
            feesUrl = "http://mock",
            client = http
        )
    }

    /*
     * Helper kept as an extension on MockRequestHandleScope because that is
     * the receiver inside MockEngine { ... } where respond() lives.
     */
    private fun MockRequestHandleScope.jsonOk(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", "application/json")
        )

    @Test
    fun `successful first response is returned without retry`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            callCount.incrementAndGet()
            jsonOk(addressInfoJson)
        }
        val info = client(engine).getAddressInfo("tb1qtest")
        assertEquals(1, callCount.get(), "Successful response must not trigger retry")
        assertEquals(1, info.txCount)
        assertEquals(100_000L, info.balance)
    }

    @Test
    fun `429 on first attempt triggers exactly one retry`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            val n = callCount.incrementAndGet()
            if (n == 1) respondError(HttpStatusCode.TooManyRequests)
            else jsonOk(addressInfoJson)
        }
        val info = client(engine).getAddressInfo("tb1qtest")
        assertEquals(2, callCount.get(),
            "429 on first attempt must trigger one retry; total = 2 calls")
        assertEquals(1, info.txCount, "Retried response must be parsed normally")
    }

    @Test
    fun `IOException on first attempt triggers exactly one retry`() = runTest {
        // Memory bug #20: original implementation only caught HttpRequestTimeoutException
        // and let plain IOException (e.g. connection reset) propagate. After the fix,
        // any IOException on the first attempt must be retried once.
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            val n = callCount.incrementAndGet()
            if (n == 1) throw IOException("Connection reset by peer")
            jsonOk(addressInfoJson)
        }
        val info = client(engine).getAddressInfo("tb1qtest")
        assertEquals(2, callCount.get(),
            "IOException on first attempt must trigger one retry")
        assertEquals(1, info.txCount)
    }

    @Test
    fun `persistent 429 across both attempts ends with RuntimeException`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            callCount.incrementAndGet()
            respondError(HttpStatusCode.TooManyRequests)
        }
        val ex = assertFailsWith<RuntimeException> {
            client(engine).getAddressInfo("tb1qtest")
        }
        assertEquals(2, callCount.get(),
            "Client must give up after exactly one retry, not loop forever")
        assertTrue(
            ex.message?.contains("rate limit") == true,
            "Diagnostic message must mention rate limit, got: ${ex.message}"
        )
    }

    @Test
    fun `non-429 4xx error fails immediately without retry`() = runTest {
        // 404 etc. are not transient - retrying just wastes a second of wall time.
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            callCount.incrementAndGet()
            respondError(HttpStatusCode.NotFound)
        }
        assertFailsWith<RuntimeException> {
            client(engine).getAddressInfo("tb1qtest")
        }
        assertEquals(1, callCount.get(),
            "Non-429 errors must fail fast without consuming a retry attempt")
    }

    @Test
    fun `IOException on both attempts surfaces a wrapped RuntimeException`() = runTest {
        val callCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            callCount.incrementAndGet()
            throw IOException("Connection reset by peer")
        }
        val ex = assertFailsWith<RuntimeException> {
            client(engine).getAddressInfo("tb1qtest")
        }
        assertEquals(2, callCount.get(),
            "Persistent IOException must still consume the one retry")
        assertTrue(
            ex.message?.contains("unreachable") == true ||
                ex.message?.contains("Mempool") == true,
            "Wrapped exception must surface the upstream context"
        )
    }

    @Test
    fun `broadcastTransaction returns the txid from the response body`() = runTest {
        // broadcastTransaction is intentionally NOT routed through getChecked
        // (no retry, since a retry on timeout could double-submit). This test
        // just verifies the happy path: POST /tx returns the txid as plain text.
        val expectedTxid = "abcd1234".repeat(8)
        val engine = MockEngine { _ ->
            respond(
                content = expectedTxid,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/plain")
            )
        }
        val txid = client(engine).broadcastTransaction("02000000aabb")
        assertEquals(expectedTxid, txid)
    }

    @Test
    fun `broadcastTransaction surfaces upstream rejection as MempoolBroadcastException`() = runTest {
        val rejectionBody = "min relay fee not met"
        val engine = MockEngine { _ ->
            respond(
                content = rejectionBody,
                status = HttpStatusCode.BadRequest,
                headers = headersOf("Content-Type", "text/plain")
            )
        }
        val ex = assertFailsWith<MempoolBroadcastException> {
            client(engine).broadcastTransaction("02000000aabb")
        }
        assertEquals(400, ex.statusCode)
        assertEquals(rejectionBody, ex.body,
            "Upstream rejection body must be surfaced verbatim for user diagnostics")
    }
}
