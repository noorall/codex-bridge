/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.noorall.codex.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CodexIdeRouterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun requireUnixSockets() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    }

    @Test(timeout = 10_000L)
    fun `multiple IDEs can initialize against the first IDE bridge`() {
        fixture().use { host ->
            val (_, firstId) = host.joinProvider()
            val (_, secondId) = host.joinProvider()

            assertTrue(firstId.isNotBlank())
            assertNotEquals(firstId, secondId)
        }
    }

    @Test(timeout = 10_000L)
    fun `routes CLI context requests to the IDE with the matching project`() {
        fixture().use { host ->
            host.provider("/pycharm")
            val cli = host.connect()

            val local = host.exchange(cli, contextRequest("/idea"))
            val remote = host.exchange(cli, contextRequest("/pycharm"))
            val missing = host.exchange(cli, contextRequest("/unrelated"))

            assertEquals("/idea", local.getAsJsonObject("result").get("workspaceRoot").asString)
            assertEquals("/pycharm", remote.getAsJsonObject("result").get("workspaceRoot").asString)
            assertEquals("context", remote.get("requestId").asString)
            assertEquals("no-client-found", missing.get("error").asString)
        }
    }

    @Test(timeout = 10_000L)
    fun `discovers contexts in either IDE and rejects an unrelated workspace`() {
        fixture().use { host ->
            host.provider("/pycharm")
            val cli = host.connect()
            fun discover(root: String): Boolean {
                val request = JsonObject().apply {
                    addProperty("type", "client-discovery-request")
                    addProperty("requestId", "discovery")
                    add("request", contextRequest(root))
                }
                return host.exchange(cli, request).getAsJsonObject("response").get("canHandle").asBoolean
            }

            assertTrue(discover("/idea"))
            assertTrue(discover("/pycharm"))
            assertFalse(discover("/unrelated"))
        }
    }

    @Test(timeout = 10_000L)
    fun `keeps concurrent requests with the same client request ID separate`() {
        fixture().use { host ->
            host.provider("/pycharm")
            val clients = (1..8).map { host.connect() }
            val replies = clients.mapIndexed { index, client ->
                host.executor.submit<JsonObject> {
                    val request = contextRequest("/pycharm").apply {
                        getAsJsonObject("params").addProperty("marker", index)
                    }
                    host.exchange(client, request)
                }
            }

            replies.forEachIndexed { index, reply ->
                val response = reply.get(5L, TimeUnit.SECONDS)
                assertEquals("context", response.get("requestId").asString)
                assertEquals(index, response.getAsJsonObject("result").get("marker").asInt)
            }
        }
    }

    @Test(timeout = 10_000L)
    fun `disconnected and unresponsive providers do not prevent local requests`() {
        fixture(responseTimeoutMillis = 50L).use { host ->
            host.provider("/pycharm").close()
            val unresponsive = host.connect()
            host.initialize(unresponsive)
            val cli = host.connect()

            val unavailable = host.exchange(cli, contextRequest("/pycharm"))
            val local = host.exchange(cli, contextRequest("/idea"))

            assertEquals("no-client-found", unavailable.get("error").asString)
            assertEquals("success", local.get("resultType").asString)
        }
    }

    @Test(timeout = 10_000L)
    fun `closing the hosting bridge releases joined IDE connections`() {
        fixture().use { host ->
            val provider = host.connect()
            host.initialize(provider)
            host.router.close()

            val closed = host.executor.submit<Boolean> {
                try {
                    provider.readFrame()
                    false
                } catch (_: IOException) {
                    true
                }
            }
            assertTrue(closed.get(5L, TimeUnit.SECONDS))
        }
    }

    private fun fixture(responseTimeoutMillis: Long = 2_000L): RouterFixture {
        val socketPath = temporaryFolder.newFolder().toPath().resolve("router.sock")
        return RouterFixture(socketPath, responseTimeoutMillis)
    }

    private class RouterFixture(private val socketPath: Path, responseTimeoutMillis: Long) : Closeable {
        val executor = Executors.newCachedThreadPool()
        val router = CodexIdeRouter(executor, responseTimeoutMillis) { handleProviderMessage(it, "/idea") }
        private val connections = ConcurrentHashMap.newKeySet<IpcConnection>()
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
        }

        init {
            executor.execute {
                try {
                    while (server.isOpen) {
                        val client = SocketChannelConnection(server.accept())
                        executor.execute {
                            try {
                                router.handleConnection(client)
                            } catch (_: IOException) {
                                // Disconnects and host shutdown are expected in these tests.
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Closing the fixture stops the accept loop.
                }
            }
        }

        fun connect(): IpcConnection {
            return SocketChannelConnection(
                SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(server.localAddress)
                },
            ).also(connections::add)
        }

        fun initialize(connection: IpcConnection): String {
            val request = JsonParser.parseString(
                """{"type":"request","requestId":"init","sourceClientId":"initializing-client","version":0,
                    "method":"initialize","params":{"clientType":"jetbrains-ide-context"}}""",
            ).asJsonObject
            val response = exchange(connection, request)
            assertEquals("success", response.get("resultType").asString)
            return response.getAsJsonObject("result").get("clientId").asString
        }

        fun joinProvider(): Pair<IpcConnection, String> {
            val initialized = initializeUnixRouterConnection(socketPath, timeoutMillis = 2_000L)
            val connection = SocketChannelConnection(initialized.channel).also(connections::add)
            return connection to initialized.clientId
        }

        fun provider(root: String): IpcConnection {
            val (connection) = joinProvider()
            executor.execute {
                try {
                    while (connection.isOpen) {
                        connection.writeFrame(handleProviderMessage(connection.readFrame(), root))
                    }
                } catch (_: IOException) {
                    // The IDE process or the hosting router has closed its connection.
                }
            }
            return connection
        }

        fun exchange(connection: IpcConnection, request: JsonObject): JsonObject {
            connection.writeFrame(request.toString())
            return JsonParser.parseString(connection.readFrame()).asJsonObject
        }

        override fun close() {
            server.close()
            router.close()
            connections.forEach { it.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS))
        }
    }

    companion object {
        private fun contextRequest(root: String): JsonObject {
            return JsonObject().apply {
                addProperty("type", "request")
                addProperty("requestId", "context")
                addProperty("method", "ide-context")
                add("params", JsonObject().apply { addProperty("workspaceRoot", root) })
            }
        }

        private fun handleProviderMessage(rawMessage: String, root: String): String {
            val message = JsonParser.parseString(rawMessage).asJsonObject
            return JsonObject().apply {
                add("requestId", message.get("requestId"))
                if (message.get("type").asString == "client-discovery-request") {
                    val request = message.getAsJsonObject("request")
                    addProperty("type", "client-discovery-response")
                    add(
                        "response",
                        JsonObject().apply {
                            addProperty("canHandle", request.getAsJsonObject("params").get("workspaceRoot").asString == root)
                        },
                    )
                } else {
                    addProperty("type", "response")
                    addProperty("resultType", "success")
                    add("result", message.getAsJsonObject("params").deepCopy())
                }
            }.toString()
        }
    }
}
