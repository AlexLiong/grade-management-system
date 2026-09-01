package edu.chd.practice.rmi.server.integrity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class GradeSnapshotCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private GradeSnapshotCodec() {
    }

    public static String encode(Map<String, String> values) {
        StringBuilder result = new StringBuilder("grade-snapshot-v1\n");
        new TreeMap<>(values).forEach((key, value) -> result.append(text(key)).append('\t')
                .append(value == null ? "~" : text(value)).append('\n'));
        return result.toString();
    }

    public static Map<String, String> decode(String encoded) {
        String[] lines = encoded.split("\\n", -1);
        if (lines.length == 0 || !"grade-snapshot-v1".equals(lines[0])) {
            throw new IllegalArgumentException("Unsupported grade snapshot format");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) continue;
            String[] pair = lines[index].split("\\t", -1);
            if (pair.length != 2) throw new IllegalArgumentException("Malformed grade snapshot field");
            values.put(value(pair[0]), "~".equals(pair[1]) ? null : value(pair[1]));
        }
        return values;
    }

    private static String text(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(String encoded) {
        return new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
    }
}
