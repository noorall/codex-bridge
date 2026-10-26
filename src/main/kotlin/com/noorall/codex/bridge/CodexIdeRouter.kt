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
import com.intellij.openapi.diagnostic.logger
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The IDE owning the shared socket also routes requests to providers in other IDE processes. */
internal class CodexIdeRouter(
    private val executor: Executor,
    private val responseTimeoutMillis: Long = 2_000L,
    private val handleLocalMessage: (String) -> String?,
) : Closeable {
    private val log = logger<CodexIdeRouter>()
    private val peers = ConcurrentHashMap.newKeySet<Peer>()
    private val pending = ConcurrentHashMap<String, PendingResponse>()

    @Volatile
    private var closed = false

    fun handleConnection(connection: IpcConnection) {
        val peer = Peer(connection)
        peers.add(peer)
        try {
            if (closed) {
                return
            }
            while (connection.isOpen) {
                val message = try {
                    JsonParser.parseString(connection.readFrame()).asJsonObject
                } catch (_: EOFException) {
                    return
                }
                when (message.get("type")?.asString) {
                    "response", "client-discovery-response" -> {
                        val requestId = message.get("requestId")?.asString
                        val response = requestId?.let(pending::get)
                        if (response?.peer === peer) {
                            response.future.complete(message)
                        }
                    }

                    "request" -> {
                        if (message.get("method")?.asString == "initialize") {
                            initialize(peer, message)
                        } else {
                            respondAsync(peer) { routeRequest(peer, message) }
                        }
                    }

                    "client-discovery-request" -> respondAsync(peer) {
                        discoveryResponse(peer, message)
                    }
                }
            }
        } finally {
            disconnect(peer)
        }
    }

    private fun initialize(peer: Peer, request: JsonObject) {
        synchronized(peer) {
            peer.isProvider = request.getAsJsonObject("params")?.get("clientType")?.asString == "jetbrains-ide-context"
            // The initialize response must precede discovery requests sent by other client threads.
            peer.write(
                JsonObject().apply {
                    addProperty("type", "response")
                    add("requestId", request.get("requestId"))
                    addProperty("resultType", "success")
                    add("result", JsonObject().apply { addProperty("clientId", peer.clientId) })
                },
            )
        }
        log.info("Registered Codex IPC client ${peer.clientId} (ideContextProvider=${peer.isProvider})")
    }

    private fun routeRequest(source: Peer, request: JsonObject): JsonObject {
        if (request.get("method")?.asString != "ide-context") {
            return errorResponse(request, "no-handler-for-request")
        }

        val discovery = discoveryRequest(request)
        if (canHandle(localResponse(discovery))) {
            return localResponse(request) ?: errorResponse(request, "context-unavailable")
        }

        for (provider in providersExcept(source)) {
            if (!canHandle(requestPeer(provider, discovery))) {
                continue
            }
            val response = requestPeer(provider, request) ?: continue
            response.add("requestId", request.get("requestId"))
            response.addProperty("handledByClientId", provider.clientId)
            return response
        }
        return errorResponse(request, "no-client-found")
    }

    private fun discoveryResponse(source: Peer, message: JsonObject): JsonObject {
        val available = canHandle(localResponse(message)) ||
            providersExcept(source).any { canHandle(requestPeer(it, message)) }
        return JsonObject().apply {
            addProperty("type", "client-discovery-response")
            add("requestId", message.get("requestId"))
            add("response", JsonObject().apply { addProperty("canHandle", available) })
        }
    }

    private fun providersExcept(source: Peer): List<Peer> {
        return peers.filter { it !== source && it.isProvider && it.connection.isOpen }
    }

    private fun localResponse(message: JsonObject): JsonObject? {
        return handleLocalMessage(message.toString())?.let { JsonParser.parseString(it).asJsonObject }
    }

    private fun discoveryRequest(request: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("type", "client-discovery-request")
            add("request", request)
        }
    }

    private fun canHandle(response: JsonObject?): Boolean {
        return response?.getAsJsonObject("response")?.get("canHandle")?.asBoolean == true
    }

    private fun requestPeer(peer: Peer, message: JsonObject): JsonObject? {
        val requestId = UUID.randomUUID().toString()
        val response = PendingResponse(peer, CompletableFuture())
        pending[requestId] = response
        try {
            peer.write(message.deepCopy().apply { addProperty("requestId", requestId) })
            return response.future.get(responseTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: IOException) {
            log.debug("Could not send Codex IPC request to IDE provider ${peer.clientId}", error)
            disconnect(peer)
        } catch (_: ExecutionException) {
            // A disconnected provider must not prevent another IDE from responding.
        } catch (error: TimeoutException) {
            log.warn("Timed out waiting for Codex IDE provider ${peer.clientId} (requestId=$requestId)", error)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pending.remove(requestId)
        }
        return null
    }

    private fun respondAsync(peer: Peer, response: () -> JsonObject) {
        executor.execute {
            try {
                peer.write(response())
            } catch (_: IOException) {
                disconnect(peer)
            }
        }
    }

    private fun errorResponse(request: JsonObject, error: String): JsonObject {
        return JsonObject().apply {
            addProperty("type", "response")
            add("requestId", request.get("requestId"))
            addProperty("resultType", "error")
            addProperty("error", error)
        }
    }

    private fun disconnect(peer: Peer) {
        peers.remove(peer)
        try {
            peer.connection.close()
        } catch (_: IOException) {
            // Continue releasing pending requests and other providers during shutdown.
        } finally {
            pending.values.filter { it.peer === peer }.forEach {
                it.future.completeExceptionally(IOException("Codex IDE provider disconnected"))
            }
        }
    }

    override fun close() {
        closed = true
        peers.toList().forEach(::disconnect)
    }

    private class Peer(val connection: IpcConnection) {
        val clientId: String = "jetbrains-" + UUID.randomUUID()

        @Volatile
        var isProvider = false

        @Synchronized
        fun write(message: JsonObject) {
            connection.writeFrame(message.toString())
        }
    }

    private data class PendingResponse(
        val peer: Peer,
        val future: CompletableFuture<JsonObject>,
    )
}
