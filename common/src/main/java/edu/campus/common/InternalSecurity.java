package edu.campus.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalSecurity extends OncePerRequestFilter {
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = r.getInputStream().readNBytes(2_000_001);
        if (body.length > 2_000_000) {
            s.sendError(413);
            return;
        }
        try {
            String caller = r.getHeader("X-Service"),
                    time = r.getHeader("X-Time"),
                    nonce = r.getHeader("X-Nonce");
            long now = Instant.now().getEpochSecond(), ts = Long.parseLong(time);
            String service = System.getProperty("campus.service");
            boolean allowed =
                    switch (service) {
                        case "gateway" -> Set.of("business", "data", "audit").contains(caller);
                        case "business", "data", "audit" -> true;
                        default -> false;
                    };
            String content =
                    r.getMethod()
                            + "\n"
                            + r.getRequestURI()
                            + "\n"
                            + time
                            + "\n"
                            + nonce
                            + "\n"
                            + Crypto.hash(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (!allowed
                    || Math.abs(now - ts) > 30
                    || nonce == null
                    || nonce.length() > 100
                    || !Crypto.equal(
                    Crypto.hmac(Settings.get(caller.toUpperCase() + "_KEY"), content),
                    r.getHeader("X-Signature"))) throw new IllegalArgumentException();
            nonces.entrySet().removeIf(e -> e.getValue() < now - 60);
            if (nonces.size() > 10000 || nonces.putIfAbsent(caller + nonce, now) != null)
                throw new IllegalArgumentException();
        } catch (Exception e) {
            s.sendError(401);
            return;
        }
        HttpServletRequestWrapper cached =
                new HttpServletRequestWrapper(r) {
                    @Override
                    public ServletInputStream getInputStream() {
                        ByteArrayInputStream in = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() {
                                return in.read();
                            }

                            public boolean isFinished() {
                                return in.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener l) {
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                };
        chain.doFilter(cached, s);
    }
}
