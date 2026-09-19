package tool.java_debug_launcher;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal Debug Adapter Protocol client that drives a debug adapter over stdio.
 *
 * <p>Message framing follows the DAP spec: a {@code Content-Length: <n>\r\n\r\n}
 * header followed by {@code n} UTF-8 bytes of JSON.
 */
final class DapClient implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final BlockingQueue<JsonObject> inbox = new LinkedBlockingQueue<>();
    private final List<JsonObject> backlog = new ArrayList<>();
    private final Thread reader;
    private volatile Throwable readError;
    private int seq = 0;

    private DapClient(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.stdout = new BufferedInputStream(process.getInputStream());
        this.reader = new Thread(this::readLoop, "dap-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /**
     * Spawns a debug adapter subprocess and returns a connected client.
     *
     * @param command the full process command (java executable + classpath + main class)
     * @return a connected {@link DapClient}
     * @throws IOException if the process cannot be started
     */
    static DapClient spawn(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return new DapClient(builder.start());
    }

    /** Sends a request and blocks until its matching response arrives. */
    JsonObject request(String command, JsonObject arguments) throws Exception {
        int requestSeq = ++seq;
        JsonObject message = new JsonObject();
        message.addProperty("seq", requestSeq);
        message.addProperty("type", "request");
        message.addProperty("command", command);
        if (arguments != null) {
            message.add("arguments", arguments);
        }
        write(message);

        return await(m -> "response".equals(typeOf(m))
                && m.has("request_seq") && m.get("request_seq").getAsInt() == requestSeq,
                DEFAULT_TIMEOUT_MS);
    }

    /** Blocks until an event with the given name arrives. */
    JsonObject awaitEvent(String event) throws Exception {
        return await(m -> "event".equals(typeOf(m))
                && m.has("event") && event.equals(m.get("event").getAsString()),
                DEFAULT_TIMEOUT_MS);
    }

    private JsonObject await(Predicate<JsonObject> predicate, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;

        for (int i = 0; i < backlog.size(); i++) {
            if (predicate.test(backlog.get(i))) {
                return backlog.remove(i);
            }
        }

        while (System.currentTimeMillis() < deadline) {
            if (readError != null) {
                throw new AssertionError("DAP reader failed: " + readError, readError);
            }
            long remaining = deadline - System.currentTimeMillis();
            JsonObject message = inbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (message == null) {
                break;
            }
            if (predicate.test(message)) {
                return message;
            }
            backlog.add(message);
        }

        throw new AssertionError("Timed out waiting for a DAP message; buffered=" + backlog
                + (readError != null ? "; reader error=" + readError : ""));
    }

    private void write(JsonObject message) throws IOException {
        byte[] body = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        synchronized (stdin) {
            stdin.write(header);
            stdin.write(body);
            stdin.flush();
        }
    }

    private void readLoop() {
        try {
            while (true) {
                String header = readHeader();
                if (header == null) {
                    return;
                }
                int contentLength = parseContentLength(header);
                byte[] body = stdout.readNBytes(contentLength);
                if (body.length < contentLength) {
                    return;
                }
                JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                inbox.add(parsed.getAsJsonObject());
            }
        } catch (Throwable t) {
            readError = t;
        }
    }

    /** Reads bytes until the {@code \r\n\r\n} header terminator, or null at EOF. */
    private String readHeader() throws IOException {
        StringBuilder header = new StringBuilder();
        while (true) {
            int next = stdout.read();
            if (next == -1) {
                return header.length() == 0 ? null : header.toString();
            }
            header.append((char) next);
            int length = header.length();
            if (length >= 4
                    && header.charAt(length - 4) == '\r' && header.charAt(length - 3) == '\n'
                    && header.charAt(length - 2) == '\r' && header.charAt(length - 1) == '\n') {
                return header.toString();
            }
        }
    }

    private static int parseContentLength(String header) {
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        throw new AssertionError("Missing Content-Length header: " + header);
    }

    private static String typeOf(JsonObject message) {
        return message.has("type") ? message.get("type").getAsString() : "";
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // The adapter may have already closed its side.
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
