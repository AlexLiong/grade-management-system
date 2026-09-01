package edu.chd.practice.rmi.contract;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalForms {
    private CanonicalForms() {
    }

    public static String value(Object value) {
        if (value == null) {
            return "-1:";
        }
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    public static String collection(Collection<?> values) {
        if (values == null) {
            return "-1:";
        }
        StringBuilder result = new StringBuilder().append(values.size()).append('[');
        values.forEach(item -> result.append(item instanceof Canonicalizable canonicalizable
                ? value(canonicalizable.canonicalForm()) : value(item)));
        return result.append(']').toString();
    }

    public static String map(Map<String, String> values) {
        if (values == null) {
            return "-1:";
        }
        StringBuilder result = new StringBuilder().append(values.size()).append('{');
        new TreeMap<>(values).forEach((key, value) -> result.append(CanonicalForms.value(key))
                .append(CanonicalForms.value(value)));
        return result.append('}').toString();
    }
}
