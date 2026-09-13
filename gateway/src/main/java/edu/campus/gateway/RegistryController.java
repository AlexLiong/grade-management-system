package edu.campus.gateway;

import edu.campus.common.*;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.bind.annotation.*;

@RestController
public class RegistryController {
    record Entry(Protocol.Registration registration, long seen) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();

    @PostMapping("/internal/register")
    public boolean register(
            @RequestBody Protocol.Registration r, @RequestHeader("X-Service") String caller) {
        URI u = URI.create(r.url());
        Set<String> hosts = Set.of(Settings.get("SERVICE_HOSTS").split(","));
        if(!hosts.contains("**")){
            ApiException.require(
                    caller.equals(r.service())
                            && hosts.contains(u.getHost())
                            && "https".equals(u.getScheme())
                            && u.getPort() > 1024
                            && u.getRawUserInfo() == null
                            && (u.getPath().isEmpty() || u.getPath().equals("/"))
                            && u.getQuery() == null
                            && u.getFragment() == null,
                    403,
                    "无效的服务注册地址");
        }
        entries.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().seen() > 20000);
        ApiException.require(entries.size() < 128 || entries.containsKey(r.instance()), 429, "注册实例过多");
        entries.put(r.instance(), new Entry(r, System.currentTimeMillis()));
        return true;
    }

    @PostMapping("/internal/discover")
    public Map<String, String> discover(@RequestBody Map<String, String> r) {
        return Map.of("url", choose(r.get("service")));
    }

    public String choose(String name) {
        var live =
                entries.values().stream()
                        .filter(
                                e ->
                                        e.registration.service().equals(name)
                                                && System.currentTimeMillis() - e.seen < 20000)
                        .sorted(Comparator.comparing(e -> e.registration.instance()))
                        .toList();
        ApiException.require(!live.isEmpty(), 503, "服务尚未就绪：" + name);
        return live.get(Math.floorMod(cursor.getAndIncrement(), live.size())).registration.url();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status",
                "UP",
                "services",
                entries.values().stream()
                        .filter(e -> System.currentTimeMillis() - e.seen < 20000)
                        .map(
                                e ->
                                        Map.of(
                                                "service", e.registration.service(), "instance", e.registration.instance()))
                        .toList());
    }
}
