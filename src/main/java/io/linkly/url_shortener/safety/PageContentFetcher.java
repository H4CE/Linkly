package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PageContentFetcher implements AutoCloseable {
    private final PublicUrlPolicy policy;
    private final SafetyProperties properties;
    // Bound concurrency too: slow destinations must not create an unlimited queue of scans.
    private final ExecutorService workers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "linkly-content-fetch");
                thread.setDaemon(true);
                return thread;
            });

    public PageContentFetcher(PublicUrlPolicy policy, SafetyProperties properties) {
        this.policy = policy;
        this.properties = properties;
    }

    public PageSnapshot fetch(String originalUrl) {
        AtomicReference<HttpGet> activeRequest = new AtomicReference<>();
        Future<PageSnapshot> result;
        try {
            result = workers.submit(() -> fetchFollowingRedirects(URI.create(originalUrl), activeRequest));
        } catch (RejectedExecutionException ex) {
            throw unavailable("Content checks are busy; please retry shortly");
        }
        try {
            // This deadline includes DNS, redirect hops, headers and the complete body read.
            return result.get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw unavailable("Destination content check timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("Destination content check interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ApiException expected) throw expected;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not fetch destination content for checking", ex.getCause());
        } finally {
            // Interrupt alone does not reliably stop a blocking socket read: cancel the HTTP request too.
            result.cancel(true);
            HttpGet request = activeRequest.getAndSet(null);
            if (request != null) request.cancel();
        }
    }

    private PageSnapshot fetchFollowingRedirects(URI original, AtomicReference<HttpGet> active) throws Exception {
        URI current = original;
        var visited = new HashSet<URI>();
        for (int hop = 0; hop <= properties.getMaxRedirects(); hop++) {
            PublicUrlPolicy.Target target = policy.resolve(current);
            if (Thread.currentThread().isInterrupted()) throw unavailable("Destination content check interrupted");
            current = target.uri();
            if (!visited.add(current)) throw unscannable("Destination has a redirect loop");

            // Pin THIS hop's validated DNS answers. The original hostname still drives Host,
            // SNI and normal TLS certificate verification; no insecure TLS bypass is enabled.
            var manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinnedResolver(target))
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(2))
                            .setSocketTimeout(Timeout.ofSeconds(2)).build()).build();
            try (var client = HttpClients.custom().setConnectionManager(manager)
                    .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                    .disableAuthCaching()
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setResponseTimeout(Timeout.ofSeconds(2))
                            .setConnectionRequestTimeout(Timeout.ofSeconds(2)).build()).build()) {
                HttpGet request = new HttpGet(current);
                request.setHeader(HttpHeaders.USER_AGENT, "Linkly-Content-Check/1.0");
                request.setHeader(HttpHeaders.ACCEPT, "text/html, application/xhtml+xml, text/plain");
                active.set(request);
                if (Thread.currentThread().isInterrupted()) throw unavailable("Destination content check interrupted");
                var response = client.executeOpen(null, request, null);
                try {
                    try {
                        int status = response.getCode();
                        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                            var location = response.getFirstHeader(HttpHeaders.LOCATION);
                            if (location == null || hop == properties.getMaxRedirects())
                                throw unscannable("Destination has too many or invalid redirects");
                            current = current.resolve(location.getValue());
                            continue; // Validate the next hop before making another network request.
                        }
                        if (status < 200 || status >= 300)
                            throw unscannable("Destination did not return a readable successful page");
                        var entity = response.getEntity();
                        if (entity == null) throw unscannable("Destination returned no page content");
                        String header = entity.getContentType();
                        if (header == null) throw unscannable("Destination did not identify its content type");
                        MediaType type;
                        try { type = MediaType.parseMediaType(header); }
                        catch (IllegalArgumentException ex) { throw unscannable("Invalid destination content type"); }
                        String mime = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
                        if (!(mime.equals("text/html") || mime.equals("application/xhtml+xml") || mime.equals("text/plain")))
                            throw unscannable("Only HTML and plain-text destinations can be checked");
                        if (entity.getContentLength() > properties.getMaxContentBytes())
                            throw unscannable("Destination page exceeds the content-check size limit");
                        // Read at most limit+1 AFTER automatic decompression, covering gzip bombs too.
                        byte[] body = entity.getContent().readNBytes(properties.getMaxContentBytes() + 1);
                        if (body.length > properties.getMaxContentBytes())
                            throw unscannable("Destination page exceeds the content-check size limit");
                        return new PageSnapshot(current, header, body);
                    } finally {
                        // Do not drain an oversized/binary/redirect response merely to reuse a connection.
                        request.cancel();
                        try { response.close(); }
                        catch (java.io.IOException ignored) {
                            // Aborting a chunked body can make close report 'socket closed'.
                            // It must not replace the completed result or the actual rejection reason.
                        }
                    }
                } finally { active.compareAndSet(request, null); }
            }
        }
        throw unscannable("Destination has too many redirects");
    }

    private DnsResolver pinnedResolver(PublicUrlPolicy.Target target) {
        return new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!host.equalsIgnoreCase(target.uri().getHost())) throw new UnknownHostException();
                return target.addresses().clone();
            }
            public String resolveCanonicalHostname(String host) { return host; }
        };
    }

    private ApiException unavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message); }
    private ApiException unscannable(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message); }

    @PreDestroy
    @Override
    public void close() { workers.shutdownNow(); }
}
