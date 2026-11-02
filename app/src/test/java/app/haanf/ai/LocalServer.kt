package app.haanf.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny HTTP server that replays canned replies and remembers what it was sent. */
class LocalServer(private val status: Int = 200, private val replies: List<String>) : AutoCloseable {
    data class Received(val path: String, val headers: Map<String, String>, val body: String)

    val received = CopyOnWriteArrayList<Received>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            received += Received(exchange.requestURI.path, headers, exchange.requestBody.readBytes().decodeToString())
            val reply = replies[(received.size - 1).coerceAtMost(replies.lastIndex)].toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
