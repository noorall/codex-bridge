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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Refreshes one loaded Codex Desktop task through the local multi-client IPC protocol. */
public final class CodexDesktopHandoff {
    private static final Gson GSON = new Gson();
    private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;
    private static final long SESSION_START_TOLERANCE_MILLIS = 5_000L;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_OWNERSHIP_DELAY = Duration.ofMillis(250);

    private CodexDesktopHandoff() {}

    /**
     * Finds the most recently active CLI rollout for the project and silently refreshes it.
     * Any unsupported platform, missing socket, malformed rollout, or IPC failure is a no-op.
     */
    public static boolean refreshCurrentSession(Path projectRoot, long terminalLaunchedAtMillis) {
        try {
            Optional<UUID> threadId = findCurrentThreadId(
                    defaultCodexHome(), projectRoot, terminalLaunchedAtMillis);
            return threadId.isPresent()
                    && refresh(
                            defaultSocketPath(),
                            threadId.get(),
                            DEFAULT_TIMEOUT,
                            DEFAULT_OWNERSHIP_DELAY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Optional<UUID> findCurrentThreadId(
            Path codexHome, Path projectRoot, long terminalLaunchedAtMillis) {
        if (codexHome == null || projectRoot == null) {
            return Optional.empty();
        }

        Path sessionsRoot = codexHome.resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return Optional.empty();
        }

        List<RolloutCandidate> candidates = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sessionsRoot, 8)) {
            paths.filter(Files::isRegularFile)
                    .filter(CodexDesktopHandoff::isRolloutFile)
                    .forEach(path -> readRolloutCandidate(path, projectRoot).ifPresent(candidates::add));
        } catch (IOException ignored) {
            return Optional.empty();
        }

        candidates.sort(Comparator.comparingLong(RolloutCandidate::modifiedAtMillis).reversed());
        long recentThreshold = terminalLaunchedAtMillis - SESSION_START_TOLERANCE_MILLIS;
        return candidates.stream()
                .filter(candidate -> candidate.modifiedAtMillis() >= recentThreshold)
                .findFirst()
                .or(() -> candidates.stream().findFirst())
                .map(RolloutCandidate::threadId);
    }

    static boolean refresh(
            Path socketPath, UUID threadId, Duration timeout, Duration ownershipDelay) {
        try {
            if (socketPath == null || threadId == null || !Files.exists(socketPath)) {
                return false;
            }
            refreshOrThrow(socketPath, threadId, timeout, ownershipDelay);
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isTargetSnapshot(JsonObject message, UUID threadId) {
        if (!"broadcast".equals(stringField(message, "type"))
                || !"thread-stream-state-changed".equals(stringField(message, "method"))) {
            return false;
        }
        JsonObject params = objectField(message, "params");
        if (params == null || !threadId.toString().equals(stringField(params, "conversationId"))) {
            return false;
        }
        JsonObject change = objectField(params, "change");
        return change != null && "snapshot".equals(stringField(change, "type"));
    }

    private static void refreshOrThrow(
            Path socketPath, UUID threadId, Duration timeout, Duration ownershipDelay)
            throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            boolean connected = channel.connect(UnixDomainSocketAddress.of(socketPath));
            SelectionKey key = channel.register(selector, 0);
            while (!connected) {
                awaitReady(selector, key, SelectionKey.OP_CONNECT, deadlineNanos);
                connected = channel.finishConnect();
            }

            UUID initializeId = UUID.randomUUID();
            writeFrame(channel, selector, key, initializeRequest(initializeId), deadlineNanos);

            String clientId = null;
            JsonObject ownerSnapshot = null;
            while (clientId == null || ownerSnapshot == null) {
                JsonObject message = readFrame(channel, selector, key, deadlineNanos);
                if ("response".equals(stringField(message, "type"))
                        && initializeId.toString().equals(stringField(message, "requestId"))) {
                    if (!"success".equals(stringField(message, "resultType"))) {
                        throw new IOException("Codex Desktop IPC initialize failed");
                    }
                    JsonObject result = objectField(message, "result");
                    clientId = result == null ? null : stringField(result, "clientId");
                    if (clientId == null || clientId.isBlank()) {
                        throw new IOException("Codex Desktop IPC returned no client ID");
                    }
                } else if (isTargetSnapshot(message, threadId)) {
                    ownerSnapshot = message;
                }
            }

            String ownerClientId = stringField(ownerSnapshot, "sourceClientId");
            JsonObject params = objectField(ownerSnapshot, "params");
            if (ownerClientId == null || ownerClientId.isBlank() || params == null) {
                throw new IOException("Codex Desktop snapshot returned no owner");
            }

            writeFrame(
                    channel,
                    selector,
                    key,
                    ownershipBroadcast(clientId, ownerClientId, params),
                    deadlineNanos);
            sleep(ownershipDelay);
        }
    }

    private static JsonObject initializeRequest(UUID requestId) {
        JsonObject params = new JsonObject();
        params.addProperty("clientType", "desktop-refresh-bridge");

        JsonObject request = new JsonObject();
        request.addProperty("type", "request");
        request.addProperty("requestId", requestId.toString());
        request.addProperty("sourceClientId", "initializing-client");
        request.addProperty("version", 0);
        request.addProperty("method", "initialize");
        request.add("params", params);
        return request;
    }

    private static JsonObject ownershipBroadcast(
            String clientId, String ownerClientId, JsonObject params) {
        JsonArray targets = new JsonArray();
        targets.add(ownerClientId);

        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "broadcast");
        broadcast.addProperty("method", "thread-stream-state-changed");
        broadcast.addProperty("sourceClientId", clientId);
        broadcast.add("targetClientIds", targets);
        broadcast.addProperty("version", 11);
        broadcast.add("params", params.deepCopy());
        return broadcast;
    }

    private static void writeFrame(
            SocketChannel channel,
            Selector selector,
            SelectionKey key,
            JsonObject message,
            long deadlineNanos)
            throws IOException {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid outgoing Codex Desktop IPC frame size");
        }
        ByteBuffer frame = ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(payload.length).put(payload).flip();
        writeFully(channel, selector, key, frame, deadlineNanos);
    }

    private static JsonObject readFrame(
            SocketChannel channel, Selector selector, SelectionKey key, long deadlineNanos)
            throws IOException {
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, selector, key, lengthBuffer, deadlineNanos);
        lengthBuffer.flip();
        int length = lengthBuffer.getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid incoming Codex Desktop IPC frame size");
        }

        ByteBuffer payload = ByteBuffer.allocate(length);
        readFully(channel, selector, key, payload, deadlineNanos);
        payload.flip();
        JsonElement value = JsonParser.parseString(StandardCharsets.UTF_8.decode(payload).toString());
        if (!value.isJsonObject()) {
            throw new IOException("Unexpected non-object Codex Desktop IPC frame");
        }
        return value.getAsJsonObject();
    }

    private static void readFully(
            SocketChannel channel,
            Selector selector,
            SelectionKey key,
            ByteBuffer buffer,
            long deadlineNanos)
            throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                throw new EOFException("Codex Desktop closed the IPC connection");
            }
            if (count == 0) {
                awaitReady(selector, key, SelectionKey.OP_READ, deadlineNanos);
            }
        }
    }

    private static void writeFully(
            SocketChannel channel,
            Selector selector,
            SelectionKey key,
            ByteBuffer buffer,
            long deadlineNanos)
            throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                awaitReady(selector, key, SelectionKey.OP_WRITE, deadlineNanos);
            }
        }
    }

    private static void awaitReady(
            Selector selector, SelectionKey key, int operation, long deadlineNanos)
            throws IOException {
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new SocketTimeoutException("Timed out waiting for Codex Desktop IPC");
            }
            key.interestOps(operation);
            long timeoutMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            int selected = selector.select(timeoutMillis);
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            boolean ready = selectedKeys.stream()
                    .anyMatch(selectedKey -> selectedKey == key && (selectedKey.readyOps() & operation) != 0);
            selectedKeys.clear();
            key.interestOps(0);
            if (selected > 0 && ready) {
                return;
            }
        }
    }

    private static void sleep(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();
        int nanos = duration.minusMillis(millis).getNano();
        Thread.sleep(millis, nanos);
    }

    private static Optional<RolloutCandidate> readRolloutCandidate(Path path, Path projectRoot) {
        try {
            long modifiedAtMillis = Files.getLastModifiedTime(path).toMillis();
            String firstLine;
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                firstLine = reader.readLine();
            }
            if (firstLine == null) {
                return Optional.empty();
            }

            JsonObject root = JsonParser.parseString(firstLine).getAsJsonObject();
            if (!"session_meta".equals(stringField(root, "type"))) {
                return Optional.empty();
            }
            JsonObject payload = objectField(root, "payload");
            if (payload == null || !isCliSession(payload)) {
                return Optional.empty();
            }
            String cwd = stringField(payload, "cwd");
            String id = stringField(payload, "id");
            if (cwd == null || id == null || !samePath(projectRoot, Path.of(cwd))) {
                return Optional.empty();
            }
            return Optional.of(new RolloutCandidate(UUID.fromString(id), modifiedAtMillis));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static boolean isCliSession(JsonObject payload) {
        return "cli".equalsIgnoreCase(stringField(payload, "source"))
                || "codex-tui".equalsIgnoreCase(stringField(payload, "originator"));
    }

    private static boolean samePath(Path left, Path right) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        try {
            return Files.isSameFile(normalizedLeft, normalizedRight);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isRolloutFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("rollout-") && name.endsWith(".jsonl");
    }

    private static Path defaultCodexHome() {
        String configured = System.getenv("CODEX_HOME");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".codex");
    }

    private static Path defaultSocketPath() throws ReflectiveOperationException {
        String configuredTemp = System.getenv("TMPDIR");
        String tempDir = configuredTemp == null || configuredTemp.isBlank()
                ? System.getProperty("java.io.tmpdir")
                : configuredTemp;
        return preferredSocketPath(defaultCodexHome(), Path.of(tempDir), currentUid());
    }

    static Path preferredSocketPath(Path codexHome, Path systemTempDir, long uid) {
        Path primarySocket = codexHome.resolve("ipc").resolve("ipc.sock");
        if (Files.exists(primarySocket)) {
            return primarySocket;
        }
        return systemTempDir.resolve("codex-ipc").resolve("ipc-" + uid + ".sock");
    }

    private static long currentUid() throws ReflectiveOperationException {
        Class<?> unixSystemClass = Class.forName("com.sun.security.auth.module.UnixSystem");
        Object unixSystem = unixSystemClass.getDeclaredConstructor().newInstance();
        return (Long) unixSystemClass.getMethod("getUid").invoke(unixSystem);
    }

    private static String stringField(JsonObject object, String name) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null
                : value.getAsString();
    }

    private static JsonObject objectField(JsonObject object, String name) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(name);
        return value == null || !value.isJsonObject() ? null : value.getAsJsonObject();
    }

    private record RolloutCandidate(UUID threadId, long modifiedAtMillis) {}
}
