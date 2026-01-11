package cz.majny.wallet.registry

import com.typesafe.config.ConfigFactory
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val cfg = ConfigFactory.load()
    val host = cfg.getString("registry.host")
    val port = cfg.getInt("registry.port")

    embeddedServer(Netty, host = host, port = port) {
        module()
    }.start(wait = true)
}