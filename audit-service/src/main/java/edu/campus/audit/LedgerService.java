package edu.campus.audit;

import edu.campus.common.*;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
    @Value("${campus.DATA_KEY}")
    private String KEY;

    public record Block(
            long index,
            String previous,
            String ciphertext,
            String hash,
            String signature,
            String transaction) {
    }

    private final Path file = Settings.root().resolve("ledger/events.jsonl");
    private final RpcClient chain = new RpcClient("audit");

    public LedgerService() throws Exception {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.createFile(file);
    }

    /**
     * Local-only verification: checks hash chain and HMAC without contacting EVM.
     */
    private List<Block> verifyLocal() throws Exception {
        List<Block> blocks = new ArrayList<>();
        String prev = "0";
        for (String line : Files.readAllLines(file)) {
            Block b = Settings.JSON.readValue(line, Block.class);
            String hash = Crypto.hash(b.index() + "|" + prev + "|" + b.ciphertext());
            ApiException.require(
                    b.index() == blocks.size()
                            && b.previous().equals(prev)
                            && Crypto.equal(hash, b.hash())
                            && Crypto.equal(b.signature(), Crypto.hmac(KEY, hash)),
                    409,
                    "独立账本校验失败");
            blocks.add(b);
            prev = hash;
        }
        return blocks;
    }

    public synchronized List<Block> verify() {
        try {
            List<Block> blocks = new ArrayList<>();
            String prev = "0";
            for (String line : Files.readAllLines(file)) {
                Block b = Settings.JSON.readValue(line, Block.class);
                String hash = Crypto.hash(b.index() + "|" + prev + "|" + b.ciphertext());
                ApiException.require(
                        b.index() == blocks.size()
                                && b.previous().equals(prev)
                                && Crypto.equal(hash, b.hash())
                                && Crypto.equal(b.signature(), Crypto.hmac(KEY, hash)),
                        409,
                        "独立账本校验失败");
                blocks.add(b);
                prev = hash;
            }
            chain.postUrl(
                    Settings.get("CHAIN_URL") + "/verify",
                    Map.of(
                            "blocks",
                            blocks.stream()
                                    .map(b -> Map.of("hash", b.hash(), "transaction", b.transaction()))
                                    .toList()),
                    Map.class);
            return blocks;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本无法校验");
        }
    }

    private Protocol.AuditEvent decode(Block b) {
        try {
            return Settings.JSON.readValue(
                    Crypto.decrypt(KEY, "" + b.index(), b.ciphertext()),
                    Protocol.AuditEvent.class);
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本解密失败");
        }
    }

    public synchronized Map<String, Object> append(Protocol.AuditEvent event) {
        var blocks = verify();
        for (Block b : blocks)
            if (decode(b).id().equals(event.id()))
                return Map.of("hash", b.hash(), "transaction", b.transaction());
        long index = blocks.size();
        String prev = blocks.isEmpty() ? "0" : blocks.get(blocks.size() - 1).hash();
        String ciphertext =
                Crypto.encrypt(KEY, "" + index, Settings.json(event));
        String hash = Crypto.hash(index + "|" + prev + "|" + ciphertext);
        var result =
                chain.postUrl(
                        Settings.get("CHAIN_URL") + "/anchor", Map.of("hash", hash, "index", index), Map.class);
        Block block =
                new Block(
                        index,
                        prev,
                        ciphertext,
                        hash,
                        Crypto.hmac(KEY, hash),
                        result.get("transaction").toString());
        try (var channel =
                     FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes =
                    ByteBuffer.wrap((Settings.json(block) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (Exception e) {
            throw new ApiException(503, "LEDGER_WRITE", "账本写入失败，需要恢复未完成的链锚定");
        }
        return Map.of("hash", hash, "transaction", block.transaction());
    }

    /**
     * Bootstrap the ledger from historical events with EVM anchoring.
     * Each block is anchored via /anchor before writing to ensure chain consistency.
     */
    public synchronized void bootstrapWithAnchors(List<Protocol.AuditEvent> events) throws Exception {
        boolean fileExists = Files.exists(file);
        if (fileExists && Files.size(file) > 0) {
            List<Block> existing = verifyLocal();
            if (!existing.isEmpty()) {
                throw new ApiException(409, "LEDGER_NOT_EMPTY", "账本已有记录，不能重新引导");
            }
        }
        if (events == null || events.isEmpty()) return;
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.createFile(file);
        String prev = "0";
        try (var channel =
                     FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (int i = 0; i < events.size(); i++) {
                Protocol.AuditEvent e = events.get(i);
                String ciphertext = Crypto.encrypt(KEY, "" + i, Settings.json(e));
                String hash = Crypto.hash(i + "|" + prev + "|" + ciphertext);
                var result = chain.postUrl(
                        Settings.get("CHAIN_URL") + "/anchor",
                        Map.of("hash", hash, "index", i), Map.class);
                Block b = new Block(
                        i, prev, ciphertext, hash,
                        Crypto.hmac(KEY, hash),
                        result.get("transaction").toString());
                ByteBuffer bytes = ByteBuffer.wrap((Settings.json(b) + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                prev = hash;
            }
            channel.force(true);
        }
    }

    /**
     * Bootstrap the ledger from historical events (e.g. demo seed). Skips EVM anchor and
     * inter-service verification; only validates the local hash chain and HMAC.
     */
    public synchronized void bootstrap(List<Protocol.AuditEvent> events) throws Exception {
        boolean fileExists = Files.exists(file);
        if (fileExists && Files.size(file) > 0) {
            List<Block> existing = verifyLocal();
            if (!existing.isEmpty()) {
                throw new ApiException(409, "LEDGER_NOT_EMPTY", "账本已有记录，不能重新引导");
            }
        }
        if (events == null || events.isEmpty()) return;
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.createFile(file);
        String prev = "0";
        try (var channel =
                     FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (int i = 0; i < events.size(); i++) {
                Protocol.AuditEvent e = events.get(i);
                String ciphertext =
                        Crypto.encrypt(KEY, "" + i, Settings.json(e));
                String hash = Crypto.hash(i + "|" + prev + "|" + ciphertext);
                Block b = new Block(
                        i, prev, ciphertext, hash,
                        Crypto.hmac(KEY, hash),
                        "bootstrap-" + i);
                ByteBuffer bytes =
                        ByteBuffer.wrap((Settings.json(b) + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                prev = hash;
            }
            channel.force(true);
        }
    }

    public synchronized Map<String, Object> read() {
        List<Block> blocks;
        try {
            blocks = verifyLocal();
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本无法校验");
        }
        var events =
                blocks.stream()
                        .map(this::decode)
                        .sorted(Comparator.comparing(Protocol.AuditEvent::time))
                        .toList();
        return Map.of(
                "verified",
                true,
                "head",
                blocks.isEmpty() ? "0" : blocks.get(blocks.size() - 1).hash(),
                "events",
                events,
                "blocks",
                blocks.stream()
                        .map(b -> Map.of("index", b.index(), "hash", b.hash(), "transaction", b.transaction()))
                        .toList());
    }

    public Map<String, Object> classify() {
        List<Block> blocks;
        try {
            blocks = verifyLocal();
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本无法校验");
        }
        var events = blocks.stream().map(this::decode).toList();
        var points =
                events.stream()
                        .map(
                                e ->
                                        Map.of(
                                                "time",
                                                e.time(),
                                                "action",
                                                e.action(),
                                                "actor",
                                                e.actor(),
                                                "count",
                                                e.changes().size()))
                        .toList();
        return chain.postUrl(
                Settings.get("CHAIN_URL") + "/classify", Map.of("events", points), Map.class);
    }
}
