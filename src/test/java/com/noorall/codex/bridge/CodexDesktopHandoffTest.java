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
package com.noorall.codex.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CodexDesktopHandoffTest {
    private static final Gson GSON = new Gson();

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void prefersCodexHomeSocketForDesktopRefresh() throws Exception {
        Path codexHome = temporaryFolder.newFolder("codex-home-ipc").toPath();
        Path systemTemp = temporaryFolder.newFolder("system-temp").toPath();
        Path primarySocket = codexHome.resolve("ipc/ipc.sock");
        Files.createDirectories(primarySocket.getParent());
        Files.createFile(primarySocket);

        assertEquals(
                primarySocket,
                CodexDesktopHandoff.preferredSocketPath(codexHome, systemTemp, 1000L));
    }

    @Test
    public void fallsBackToSystemTempForDesktopRefresh() throws Exception {
        Path codexHome = temporaryFolder.newFolder("codex-home-without-ipc").toPath();
        Path systemTemp = temporaryFolder.newFolder("desktop-system-temp").toPath();

        assertEquals(
                systemTemp.resolve("codex-ipc/ipc-1000.sock"),
                CodexDesktopHandoff.preferredSocketPath(codexHome, systemTemp, 1000L));
    }

    @Test
    public void findsMostRecentCliRolloutForCurrentProject() throws Exception {
        Path codexHome = temporaryFolder.newFolder("codex-home").toPath();
        Path sessions = codexHome.resolve("sessions/2026/07/15");
        Path project = temporaryFolder.newFolder("project").toPath();
        Path otherProject = temporaryFolder.newFolder("other-project").toPath();
        Files.createDirectories(sessions);

        UUID expected = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID desktop = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long now = System.currentTimeMillis();

        writeRollout(sessions.resolve("rollout-old.jsonl"), older, project, "cli", now - 2_000L);
        writeRollout(sessions.resolve("rollout-current.jsonl"), expected, project, "cli", now);
        writeRollout(sessions.resolve("rollout-desktop.jsonl"), desktop, project, "app", now + 2_000L);
        writeRollout(sessions.resolve("rollout-other.jsonl"), other, otherProject, "cli", now + 3_000L);

        Optional<UUID> resolved =
                CodexDesktopHandoff.findCurrentThreadId(codexHome, project, now - 1_000L);

        assertEquals(Optional.of(expected), resolved);
    }

    @Test
    public void transfersSnapshotOwnershipToDesktopRenderer() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path socketPath = temporaryFolder.newFolder("socket").toPath().resolve("ipc.sock");
        UUID threadId = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            Future<JsonObject> receivedBroadcast = executor.submit(() -> serveDesktopHandshake(server, threadId));

            assertTrue(CodexDesktopHandoff.refresh(
                    socketPath, threadId, Duration.ofSeconds(2), Duration.ZERO));

            JsonObject broadcast = receivedBroadcast.get();
            assertEquals("broadcast", broadcast.get("type").getAsString());
            assertEquals("thread-stream-state-changed", broadcast.get("method").getAsString());
            assertEquals("refresh-client", broadcast.get("sourceClientId").getAsString());
            assertEquals(11, broadcast.get("version").getAsInt());
            assertEquals("desktop-owner", broadcast.getAsJsonArray("targetClientIds").get(0).getAsString());
            assertEquals(
                    threadId.toString(),
                    broadcast.getAsJsonObject("params").get("conversationId").getAsString());
        } finally {
            executor.shutdownNow();
        }
    }

    private static JsonObject serveDesktopHandshake(ServerSocketChannel server, UUID threadId)
            throws Exception {
        try (SocketChannel client = server.accept()) {
            JsonObject initialize = readFrame(client);
            String requestId = initialize.get("requestId").getAsString();

            JsonObject result = new JsonObject();
            result.addProperty("clientId", "refresh-client");
            JsonObject response = new JsonObject();
            response.addProperty("type", "response");
            response.addProperty("requestId", requestId);
            response.addProperty("resultType", "success");
            response.add("result", result);
            writeFrame(client, response);

            writeFrame(client, snapshot(UUID.randomUUID()));
            writeFrame(client, snapshot(threadId));
            return readFrame(client);
        }
    }

    private static JsonObject snapshot(UUID threadId) {
        JsonObject change = new JsonObject();
        change.addProperty("type", "snapshot");
        JsonObject params = new JsonObject();
        params.addProperty("conversationId", threadId.toString());
        params.add("change", change);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("type", "broadcast");
        snapshot.addProperty("method", "thread-stream-state-changed");
        snapshot.addProperty("sourceClientId", "desktop-owner");
        snapshot.add("params", params);
        return snapshot;
    }

    private static void writeRollout(
            Path path, UUID id, Path cwd, String source, long modifiedAtMillis) throws Exception {
        String sessionMeta = GSON.toJson(Map.of(
                "type",
                "session_meta",
                "payload",
                Map.of(
                        "id", id.toString(),
                        "cwd", cwd.toString(),
                        "source", source,
                        "originator", source.equals("cli") ? "codex-tui" : "codex-desktop")));
        Files.writeString(path, sessionMeta + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedAtMillis));
    }

    private static JsonObject readFrame(SocketChannel channel) throws Exception {
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, lengthBuffer);
        lengthBuffer.flip();
        ByteBuffer payload = ByteBuffer.allocate(lengthBuffer.getInt());
        readFully(channel, payload);
        payload.flip();
        return JsonParser.parseString(StandardCharsets.UTF_8.decode(payload).toString())
                .getAsJsonObject();
    }

    private static void writeFrame(SocketChannel channel, JsonObject message) throws Exception {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(payload.length).put(payload).flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IllegalStateException("IPC connection closed early");
            }
        }
    }
}
