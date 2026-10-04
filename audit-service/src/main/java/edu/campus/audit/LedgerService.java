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

    // ---------------------------------------------------------------- 校验缓存
    //
    // 账本是**只追加**的：已写下的区块不会被改写，除非有人动了文件。而 /audit、/integrity 每次
    // 刷新都会重新走一遍「逐块哈希 + HMAC」与「逐条 AES 解密」，区块上千后就是几秒级开销。
    // 这里按文件状态做两级缓存，文件一有变化就自动失效，因此不会掩盖任何篡改：
    //   localVerified   —— 已通过「哈希链 + HMAC」校验的区块，以及当时的文件状态
    //   fullVerified    —— 已在 localVerified 基础上再过一遍 EVM 锚点校验的区块
    private static final class Snapshot {
        final List<Block> blocks;
        final long size;
        final long modified;

        Snapshot(List<Block> blocks, long size, long modified) {
            this.blocks = blocks;
            this.size = size;
            this.modified = modified;
        }

        /** 当前文件是否与快照时一致；不一致说明账本被追加或改写，缓存必须失效。 */
        boolean matches(Path file) {
            try {
                return Files.size(file) == size && Files.getLastModifiedTime(file).toMillis() == modified;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private Snapshot localVerified;
    private Snapshot fullVerified;

    private long fileSize() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private long fileModified() {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 账本文件变化后清空两级缓存。 */
    private void invalidate() {
        localVerified = null;
        fullVerified = null;
    }

    public LedgerService() throws Exception {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.createFile(file);
    }

    /**
     * 逐块校验「哈希链 + HMAC」，带缓存：文件未变化时直接复用上次结果。
     *
     * @param full true 表示这次要做**完整**校验（哈希链 + HMAC + EVM 锚点），因此在
     *     {@link #localVerified} 的基础上继续走到 {@link #fullVerified}，普通读取只需前者。
     */
    private synchronized List<Block> verified(boolean full) throws Exception {
        Snapshot base = full ? fullVerified : localVerified;
        if (base != null && base.matches(file)) return base.blocks;

        Snapshot local = localVerified;
        if (local == null || !local.matches(file)) {
            local = new Snapshot(verifyLocal(), fileSize(), fileModified());
            localVerified = local;
            fullVerified = null; // 链变了，之前的 EVM 校验结果不再对应当前账本
        }
        requireNotTruncated(local.blocks.size());
        if (!full) return local.blocks;

        chain.postUrl(
                Settings.get("CHAIN_URL") + "/verify",
                Map.of(
                        "blocks",
                        local.blocks.stream()
                                .map(b -> Map.of("hash", b.hash(), "transaction", b.transaction()))
                                .toList()),
                Map.class);
        fullVerified = local;
        return local.blocks;
    }

    /**
     * 拒绝比独立锚点更短的账本。
     *
     * <p>{@link #verifyLocal()} 只检查「区块之间的链接」，因此**从尾部截断区块不会破坏任何哈希
     * 链接**——删掉最后 N 块后，剩下的链依然自洽，读取会静默少返回 N 条审计记录。锚点文件里记录
     * 了已锚定到 EVM 的区块数，用它做「本地账本不得比链上更短」的检查，补上这个缺口。
     *
     * <p>只检查「更短」：账本比锚点多属于正常中间态（新区块先落本地账本，再逐块锚定）。
     * 探测不到锚点文件（例如首次运行或单元测试环境）时跳过，不影响正常启动。
     */
    private void requireNotTruncated(int localBlocks) {
        Integer anchored = anchoredBlocks();
        if (anchored == null) return;
        ApiException.require(
                localBlocks >= anchored,
                409,
                "独立账本比链上锚点更短（本地 " + localBlocks + " 块 / 锚点 " + anchored + " 块），疑似被截断");
    }

    private Integer anchoredBlocks() {
        try {
            Path anchors = Settings.root().resolve("anchors.json");
            if (!Files.exists(anchors)) return null;
            var parsed = Settings.JSON.readValue(Files.readString(anchors), List.class);
            return parsed == null ? null : parsed.size();
        } catch (Exception e) {
            return null; // 锚点不可读时不阻断读取，完整校验仍会覆盖 EVM 一侧
        }
    }

    /**
     * Local-only verification: checks hash chain and HMAC without contacting EVM.
     *
     * <p>这里**每次都完整重算**，缓存由 {@link #verified(boolean)} 负责——保持本方法语义纯粹，
     * 便于单独测试与理解。
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

    /** 完整校验：哈希链 + HMAC + EVM 锚点。结果按文件状态缓存，账本未变时直接复用。 */
    public synchronized List<Block> verify() {
        try {
            return verified(true);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本无法校验");
        }
    }

    private Protocol.AuditEvent decode(Block b) {
        try {
            return reveal(
                    Settings.JSON.readValue(
                            Crypto.decrypt(KEY, "" + b.index(), b.ciphertext()),
                            Protocol.AuditEvent.class));
        } catch (Exception e) {
            throw new ApiException(409, "LEDGER_CORRUPT", "独立账本解密失败");
        }
    }

    /** 账本快照中以密文保存的字段（数据库与账本均只保存密文）。 */
    private static final List<String> SNAPSHOT_SECRETS = List.of("payload", "content");

    /**
     * 快照解密：账本里保存的是密文，明文只在本服务读取时还原。
     * 快照密文格式为 base64url(aad) + "~" + salt:iv:ct，AAD 随密文一起保存，
     * 因此历史快照不需要依赖行当前状态即可解开。
     */
    private Protocol.AuditEvent reveal(Protocol.AuditEvent event) {
        return new Protocol.AuditEvent(
                event.id(),
                event.actor(),
                event.action(),
                event.resource(),
                event.time(),
                event.changes().stream()
                        .map(
                                change -> {
                                    var copy = new LinkedHashMap<String, Object>(change);
                                    copy.put("before", revealRow(change.get("before")));
                                    copy.put("after", revealRow(change.get("after")));
                                    return (Map<String, Object>) copy;
                                })
                        .toList());
    }

    private Map<String, Object> revealRow(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> raw) || raw.isEmpty()) return null;
        var row = new LinkedHashMap<String, Object>();
        raw.forEach((k, v) -> row.put(String.valueOf(k), v));
        for (String field : SNAPSHOT_SECRETS) {
            Object value = row.get(field);
            if (!(value instanceof String sealed) || sealed.indexOf('~') < 0) continue;
            int split = sealed.indexOf('~');
            String aad =
                    new String(
                            Base64.getUrlDecoder().decode(sealed.substring(0, split)),
                            StandardCharsets.UTF_8);
            row.put(field, Crypto.decrypt(KEY, aad, sealed.substring(split + 1)));
        }
        return row;
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
        // 显式失效，不依赖「文件大小 + mtime」的变化：同一毫秒内的连续写入可能让文件时间戳
        // 不变，只靠 matches() 判断会读到不含刚写入区块的旧缓存。
        invalidate();
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
        invalidate();
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
        invalidate();
    }

    /**
     * 清空独立账本。仅在演示库重建时使用：数据库结构版本变化会删除全部业务表并重新灌入
     * 数据，旧账本里的成绩快照已无对应行，必须与数据库一起重建，否则 {@code /integrity}
     * 会把历史快照判成缺失。
     *
     * <p>该方法只清空本机教学账本文件，不触碰 EVM；锚定过的旧摘要按设计留在链上，
     * 重建后会按新数据重新锚定。
     */
    public synchronized Map<String, Object> reset() throws Exception {
        long before = Files.exists(file) ? Files.size(file) : 0;
        List<Block> existing;
        try {
            existing = Files.exists(file) ? verifyLocal() : List.of();
        } catch (ApiException e) {
            // 旧账本与当前密钥不匹配（例如重新初始化过运行配置）时同样允许重建。
            existing = List.of();
        }
        // 先清空 EVM 侧的锚点：旧摘要与新链无关，留着会让重新锚定因
        // "Anchor conflict"（同一下标不同哈希）失败。
        Object chainResult = null;
        try {
            chainResult = chain.postUrl(Settings.get("CHAIN_URL") + "/reset", Map.of(), Map.class);
        } catch (Exception e) {
            System.err.println("[LedgerService] 链锚点重置失败（继续重建账本）：" + e.getMessage());
        }
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        invalidate();
        System.out.println(
            "[LedgerService] 演示账本已重建：清除 "
                + before
                + " 字节，"
                + existing.size()
                + " 个区块；链锚点重置结果 "
                + chainResult);
        return Map.of("ok", true, "removedBlocks", existing.size(), "chain", Objects.toString(chainResult, "unavailable"));
    }

    public synchronized Map<String, Object> read() {
        List<Block> blocks;
        try {
            // 读取只需要「哈希链 + HMAC」这一层，不必每次再跑一遍 EVM 锚点校验。
            blocks = verified(false);
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
            blocks = verified(false);
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
