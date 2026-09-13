package edu.campus.common;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;

public class RpcClient {
    private final String caller;
    private final HttpClient client;

    public RpcClient(String caller) {
        this.caller = caller;
        this.client = buildClient();
    }

    private HttpClient buildClient() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            Path truststorePath = Settings.root().resolve("truststore.p12");
            if (java.nio.file.Files.exists(truststorePath)) {
                try (var is = java.nio.file.Files.newInputStream(truststorePath)) {
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    ks.load(is, "campus-dev-tls-2024".toCharArray());
                    tmf.init(ks);
                }
            } else {
                tmf.init((KeyStore) null);
            }
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .sslContext(sslCtx)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init RpcClient truststore", e);
        }
    }

    public HttpResponse<String> raw(
            String url, String method, String body, Map<String, String> extra) {
        try {
            URI uri = URI.create(url);
            String time = "" + Instant.now().getEpochSecond(), nonce = Crypto.random();
            String sig =
                    Crypto.hmac(
                            Settings.get(caller.toUpperCase() + "_KEY"),
                            method
                                    + "\n"
                                    + uri.getRawPath()
                                    + "\n"
                                    + time
                                    + "\n"
                                    + nonce
                                    + "\n"
                                    + Crypto.hash(body));
            HttpRequest.Builder b =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(25))
                            .header("Content-Type", "application/json")
                            .header("X-Service", caller)
                            .header("X-Time", time)
                            .header("X-Nonce", nonce)
                            .header("X-Signature", sig);
            extra.forEach(b::header);
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试:" + e.getMessage());
        }
    }

    public <T> T postUrl(String url, Object data, Class<T> type) {
        var r = raw(url, "POST", Settings.json(data), Map.of());
        if (r.statusCode() >= 400) {
            try {
                var e = Settings.JSON.readTree(r.body());
                throw new ApiException(
                        r.statusCode(),
                        e.path("error").asText("RPC_ERROR"),
                        e.path("message").asText("远程服务拒绝请求"));
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException(503, "RPC_ERROR", "远程服务异常");
            }
        }
        try {
            return Settings.JSON.readValue(r.body(), type);
        } catch (Exception e) {
            throw new ApiException(503, "RPC_FORMAT", "远程响应格式异常");
        }
    }

    public String discover(String service) {
        String url = Settings.get("GATEWAY_URL") + "/internal/discover";
        String string = postUrl(
                url,
                Map.of("service", service),
                Map.class)
                .get("url")
                .toString();
        return string;
    }

    public <T> T post(String service, String path, Object data, Class<T> type) {
        var url = discover(service) + path;
        return postUrl(url, data, type);
    }
}
