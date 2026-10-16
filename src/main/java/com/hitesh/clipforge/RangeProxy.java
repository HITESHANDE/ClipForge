package com.hitesh.clipforge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local relay that ffmpeg reads remote streams through.
 *
 * Why: YouTube (and some other hosts) throttle a single open-ended HTTP request — which is
 * exactly what ffmpeg makes — to roughly 2x real time, while bounded range requests are served
 * at full speed (measured on this machine: 10 MB in 1.9 s by range vs ~2x real time through
 * ffmpeg). The relay answers ffmpeg's request on 127.0.0.1 and fills it by fetching the remote
 * file in bounded chunks, prefetching the next chunk while the current one is being written.
 * Seeking keeps working: every ffmpeg seek is just a new ranged request to the relay.
 */
final class RangeProxy {
    private static final long CHUNK = 4L * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final HttpServer server;
    private final HttpClient client;
    private final Map<String, String> targets = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    RangeProxy() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "range-proxy");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/s/", this::handle);
        server.start();
        client = HttpClient.newBuilder()
                // Plain HTTP/1.1 like curl/ffmpeg: googlevideo answered the Java client's default
                // HTTP/2 + "Java-http-client" agent with 403 Forbidden.
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "range-proxy-client");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    /** Returns a local URL that serves the same bytes as {@code remote}. Non-HTTP inputs pass through. */
    String wrap(String remote) {
        if (remote == null || !(remote.startsWith("http://") || remote.startsWith("https://"))) return remote;
        String id = Long.toString(ids.incrementAndGet());
        targets.put(id, remote);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/s/" + id;
    }

    void stop() { server.stop(0); }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private HttpResponse<InputStream> fetch(String url, long from, long to) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=" + from + "-" + to)
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    }

    private CompletableFuture<HttpResponse<byte[]>> prefetch(String url, long from, long to) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=" + from + "-" + to)
                .header("User-Agent", UA)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String id = ex.getRequestURI().getPath().substring(3);
            String url = targets.get(id);
            if (url == null) { ex.sendResponseHeaders(404, -1); return; }

            long start = 0, reqEnd = -1;
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) {
                Matcher m = RANGE.matcher(range.trim());
                if (m.matches()) {
                    if (!m.group(1).isEmpty()) start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) reqEnd = Long.parseLong(m.group(2));
                }
            }

            // First chunk also tells us the total size.
            long firstTo = reqEnd >= 0 ? Math.min(reqEnd, start + CHUNK - 1) : start + CHUNK - 1;
            HttpResponse<InputStream> first;
            try { first = fetch(url, start, firstTo); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); ex.sendResponseHeaders(502, -1); return; }

            int code = first.statusCode();
            if (code == 416) { first.body().close(); ex.sendResponseHeaders(416, -1); return; }
            if (code != 206 && code != 200) {
                first.body().close();
                ex.sendResponseHeaders(code >= 400 ? code : 502, -1);
                return;
            }

            long total = -1;
            if (code == 206) {
                Matcher cr = CONTENT_RANGE.matcher(first.headers().firstValue("Content-Range").orElse(""));
                if (cr.find() && !cr.group(3).equals("*")) total = Long.parseLong(cr.group(3));
            } else {
                // Server ignored ranges: just relay the whole body as-is.
                total = first.headers().firstValueAsLong("Content-Length").orElse(-1);
                start = 0;
            }
            long end = total > 0 ? (reqEnd >= 0 ? Math.min(reqEnd, total - 1) : total - 1) : -1;

            first.headers().firstValue("Content-Type").ifPresent(ct -> ex.getResponseHeaders().set("Content-Type", ct));
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            boolean isHead = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
            if (code == 206 && end >= 0) {
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
                if (isHead) { first.body().close(); ex.sendResponseHeaders(206, -1); return; }
                ex.sendResponseHeaders(206, end - start + 1);
            } else {
                if (isHead) { first.body().close(); ex.sendResponseHeaders(200, -1); return; }
                ex.sendResponseHeaders(200, total > 0 ? total : 0);
            }

            try (OutputStream out = ex.getResponseBody()) {
                long pos;
                CompletableFuture<HttpResponse<byte[]>> next = null;
                long chunkEnd = Math.min(firstTo, end >= 0 ? end : Long.MAX_VALUE);
                if (code == 206 && end >= 0 && chunkEnd < end) {
                    next = prefetch(url, chunkEnd + 1, Math.min(end, chunkEnd + CHUNK));
                }
                try (InputStream in = first.body()) { in.transferTo(out); }
                if (code != 206 || end < 0) return;
                pos = chunkEnd + 1;
                while (pos <= end) {
                    long to = Math.min(end, pos + CHUNK - 1);
                    HttpResponse<byte[]> r;
                    try {
                        r = (next != null) ? next.get() : prefetch(url, pos, to).get();
                    } catch (Exception e) {
                        throw new IOException("upstream chunk failed", e);
                    }
                    if (r.statusCode() != 206 && r.statusCode() != 200) throw new IOException("upstream HTTP " + r.statusCode());
                    long after = to + 1;
                    next = after <= end ? prefetch(url, after, Math.min(end, after + CHUNK - 1)) : null;
                    out.write(r.body());
                    pos = after;
                }
            } catch (IOException clientGone) {
                // ffmpeg closed the connection (seek or done) — normal; stop fetching.
            }
        } finally {
            ex.close();
        }
    }
}
