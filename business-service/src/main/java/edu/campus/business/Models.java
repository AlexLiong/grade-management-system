package edu.campus.business;

import edu.campus.common.*;

import java.util.*;

public final class Models {
    private Models() {
    }

    public static final List<String> COMPONENTS =
            List.of("regular", "attendance", "homework", "lab", "midterm", "finalExam");
    public static final Map<String, Set<String>> PERMISSIONS =
            Map.of(
                    "TEACHER",
                    Set.of("QUERY", "ENTRY", "MAINTAIN", "PREDICT"),
                    "STUDENT",
                    Set.of("QUERY", "PREDICT"),
                    "ADMIN",
                    Set.of("GRADE_ADMIN", "USER_ADMIN", "AUDIT"));

    public record User(
            String id, String username, String name, String role, Set<String> permissions, int version) {
        public void require(String permission) {
            ApiException.require(permissions.contains(permission), 403, "没有此操作权限");
        }
    }

    public static int integer(Object x) {
        return Integer.parseInt(x.toString());
    }

    public static double number(Object x) {
        double d = Double.parseDouble(x.toString());
        ApiException.require(Double.isFinite(d), 400, "必须是有限数值");
        return d;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        try {
            return value instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) value)
                    : Settings.JSON.readValue(value.toString(), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid object");
        }
    }

    public static String text(Map<String, Object> m, String key, int max) {
        Object v = m.get(key);
        ApiException.require(
                v instanceof String && !v.toString().isBlank() && v.toString().length() <= max,
                400,
                "字段无效：" + key);
        return v.toString().strip();
    }

    public static Map<String, Object> publicUser(Map<String, Object> user) {
        var copy = new LinkedHashMap<>(user);
        copy.remove("password");
        return copy;
    }

    public static Map<String, Object> grade(Map<String, Object> row) {
        var copy = new LinkedHashMap<>(row);
        copy.put("scores", object(copy.remove("payload")));
        return copy;
    }


    public static Double total(Map<String, Object> scores, Map<String, Object> weights) {
        double sum = 0;
        for (String key : COMPONENTS) {
            double w = number(weights.get(key));
            if (w > 0 && scores.get(key) == null) return null;
            if (w > 0) sum += number(scores.get(key)) * w / 100;
        }
        return Math.round(sum * 100) / 100.0;
    }

    public static Double effective(Map<String, Object> scores, Map<String, Object> weights) {
        Double regular = total(scores, weights);
        if (regular == null) return null;
        return scores.get("makeup") == null
                ? regular
                : Math.max(regular, Math.min(60, number(scores.get("makeup"))));
    }
}
