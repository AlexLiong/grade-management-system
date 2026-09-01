package edu.chd.practice.web.service;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

final class SemanticFingerprint {
    private SemanticFingerprint() {
    }

    static String of(String operation, Object... values) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, operation);
        for (Object value : values) {
            append(canonical, value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("N;");
            return;
        }
        if (value instanceof BigDecimal decimal) {
            scalar(target, "D", decimal.stripTrailingZeros().toPlainString());
            return;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?>) {
            scalar(target, "S", value instanceof Enum<?> item ? item.name() : String.valueOf(value));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            target.append("M").append(map.size()).append('{');
            map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        append(target, String.valueOf(entry.getKey()));
                        append(target, entry.getValue());
                    });
            target.append("};");
            return;
        }
        if (value instanceof Collection<?> collection) {
            target.append("L").append(collection.size()).append('[');
            collection.forEach(item -> append(target, item));
            target.append("]; ");
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            target.append("A").append(length).append('[');
            for (int index = 0; index < length; index++) {
                append(target, Array.get(value, index));
            }
            target.append("]; ");
            return;
        }
        if (value.getClass().isRecord()) {
            target.append("R").append(value.getClass().getName()).append('{');
            for (var component : value.getClass().getRecordComponents()) {
                append(target, component.getName());
                try {
                    append(target, component.getAccessor().invoke(value));
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    throw new IllegalArgumentException("Cannot canonicalize record " + value.getClass().getName(),
                            exception);
                }
            }
            target.append("};");
            return;
        }
        scalar(target, "O", String.valueOf(value));
    }

    private static void scalar(StringBuilder target, String type, String value) {
        target.append(type).append(value.length()).append(':').append(value).append(';');
    }
}
