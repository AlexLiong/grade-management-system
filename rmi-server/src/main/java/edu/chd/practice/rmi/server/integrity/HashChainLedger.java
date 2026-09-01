package edu.chd.practice.rmi.server.integrity;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.dto.IntegrityReport;
import edu.chd.practice.rmi.contract.dto.RecoveryQuery;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.server.config.LedgerProperties;
import edu.chd.practice.rmi.server.security.KeyDerivation;
import edu.chd.practice.rmi.server.security.SecretMaterialProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Component
public class HashChainLedger {
    private static final String GENESIS_HASH = "0".repeat(64);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String KEY_CONTEXT = "integrity-ledger-hmac-v1";

    private final Path path;
    private final byte[] key;
    private final Clock clock;

    @Autowired
    public HashChainLedger(LedgerProperties properties, SecretMaterialProvider secrets) {
        this(Path.of(properties.getPath()), secrets.hmacKey(), Clock.systemUTC());
    }

    HashChainLedger(Path path, byte[] key, Clock clock) {
        this.path = path.toAbsolutePath().normalize();
        this.key = KeyDerivation.hmacSha256(key, KEY_CONTEXT);
        this.clock = clock;
    }

    public synchronized void appendBatch(List<LedgerEvent> events) {
        if (events.isEmpty()) return;
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 FileLock ignored = channel.lock()) {
                ParsedLedger parsed = parse();
                if (!parsed.report().isValid()) {
                    throw new IllegalStateException("Integrity ledger is damaged at sequence "
                            + parsed.report().getFirstInvalidSequence());
                }
                long sequence = parsed.entries().size();
                String previousHash = parsed.entries().isEmpty() ? GENESIS_HASH
                        : parsed.entries().get(parsed.entries().size() - 1).entryHash();
                StringBuilder output = new StringBuilder();
                for (LedgerEvent event : events) {
                    sequence++;
                    long createdAt = clock.millis();
                    String hash = calculateHash(sequence, createdAt, event.eventType(), event.aggregateType(),
                            event.aggregateId(), event.actor(), event.encryptedSnapshot(), previousHash);
                    output.append(sequence).append('\t').append(createdAt).append('\t')
                            .append(encode(event.eventType())).append('\t')
                            .append(encode(event.aggregateType())).append('\t')
                            .append(encode(event.aggregateId())).append('\t')
                            .append(encode(event.actor())).append('\t')
                            .append(encode(event.encryptedSnapshot())).append('\t')
                            .append(previousHash).append('\t').append(hash).append('\n');
                    previousHash = hash;
                }
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(output.toString());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to append the external integrity ledger", exception);
        }
    }

    public synchronized IntegrityReport verify() {
        return parse().report();
    }

    public synchronized RecoveryRecord[] read(RecoveryQuery query) {
        if (query.getLimit() < 1 || query.getLimit() > 100) {
            throw new IllegalArgumentException("Recovery evidence limit must be between 1 and 100");
        }
        ParsedLedger parsed = parse();
        if (!parsed.report().isValid()) {
            throw new IllegalStateException("Integrity ledger is damaged; recovery evidence cannot be trusted");
        }
        List<RecoveryRecord> matches = new ArrayList<>();
        for (int index = parsed.entries().size() - 1; index >= 0 && matches.size() < query.getLimit(); index--) {
            StoredEntry entry = parsed.entries().get(index);
            if (query.getAggregateType().equals(entry.aggregateType())
                    && query.getAggregateId().equals(entry.aggregateId())) {
                matches.add(entry.toRecoveryRecord());
            }
        }
        return matches.toArray(RecoveryRecord[]::new);
    }

    public synchronized RecoveryRecord readSequence(long sequence) {
        ParsedLedger parsed = parse();
        if (!parsed.report().isValid()) {
            throw new IllegalStateException("Integrity ledger is damaged; recovery evidence cannot be trusted");
        }
        if (sequence < 1 || sequence > parsed.entries().size()) {
            throw new IllegalArgumentException("Recovery sequence does not exist: " + sequence);
        }
        return parsed.entries().get(Math.toIntExact(sequence - 1)).toRecoveryRecord();
    }

    Path path() {
        return path;
    }

    private ParsedLedger parse() {
        if (!Files.exists(path)) return new ParsedLedger(List.of(), new IntegrityReport(true, 0, null, "Ledger is empty"));
        List<StoredEntry> entries = new ArrayList<>();
        String previous = GENESIS_HASH;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                long expectedSequence = index + 1L;
                String line = lines.get(index);
                try {
                    String[] parts = line.split("\\t", -1);
                    if (parts.length != 9) return invalid(entries, expectedSequence, "Malformed ledger entry");
                    StoredEntry entry = new StoredEntry(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                            decode(parts[2]), decode(parts[3]), decode(parts[4]), decode(parts[5]),
                            decode(parts[6]), parts[7], parts[8]);
                    if (entry.sequence() != expectedSequence || !previous.equals(entry.previousHash())) {
                        return invalid(entries, expectedSequence, "Broken sequence or previous hash");
                    }
                    String expectedHash = calculateHash(entry.sequence(), entry.createdAt(), entry.eventType(),
                            entry.aggregateType(), entry.aggregateId(), entry.actor(), entry.encryptedSnapshot(),
                            entry.previousHash());
                    if (!constantEquals(expectedHash, entry.entryHash())) {
                        return invalid(entries, expectedSequence, "Entry authentication failed");
                    }
                    entries.add(entry);
                    previous = entry.entryHash();
                } catch (RuntimeException exception) {
                    return invalid(entries, expectedSequence, "Malformed ledger value");
                }
            }
            return new ParsedLedger(List.copyOf(entries),
                    new IntegrityReport(true, entries.size(), null, "Hash chain is valid"));
        } catch (IOException exception) {
            return new ParsedLedger(List.copyOf(entries),
                    new IntegrityReport(false, entries.size(), entries.size() + 1L, "Ledger cannot be read"));
        }
    }

    private ParsedLedger invalid(List<StoredEntry> entries, long sequence, String message) {
        return new ParsedLedger(List.copyOf(entries), new IntegrityReport(false, entries.size(), sequence, message));
    }

    private String calculateHash(long sequence, long createdAt, String eventType, String aggregateType,
                                 String aggregateId, String actor, String encryptedSnapshot,
                                 String previousHash) {
        String canonical = CanonicalForms.value(sequence) + CanonicalForms.value(createdAt)
                + CanonicalForms.value(eventType) + CanonicalForms.value(aggregateType)
                + CanonicalForms.value(aggregateId) + CanonicalForms.value(actor)
                + CanonicalForms.value(encryptedSnapshot) + CanonicalForms.value(previousHash);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record ParsedLedger(List<StoredEntry> entries, IntegrityReport report) { }

    private record StoredEntry(long sequence, long createdAt, String eventType, String aggregateType,
                               String aggregateId, String actor, String encryptedSnapshot,
                               String previousHash, String entryHash) {
        RecoveryRecord toRecoveryRecord() {
            return new RecoveryRecord(sequence, eventType, aggregateType, aggregateId, encryptedSnapshot,
                    previousHash, entryHash, createdAt, actor);
        }
    }
}
