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
                    Set.of("QUERY", "PREDICT", "SELECTION_ENROLL"),
                    "ADMIN",
                    Set.of(
                            "GRADE_ADMIN",
                            "USER_ADMIN",
                            "AUDIT",
                            "ORG_ADMIN",
                            "SELECTION_ADMIN"));

    /** 学院的编号前缀，与专业、班级一起构成可读的业务主键。 */
    public static final String COLLEGE_PREFIX = "C";

    public static final String MAJOR_PREFIX = "M";

    public static final String CLASS_PREFIX = "B";

    /** 选课发布状态。 */
    public static final Set<String> SELECTION_STATUS = Set.of("OPEN", "CLOSED", "CANCELLED");

    /** 课程状态。 */
    public static final Set<String> COURSE_STATUS = Set.of("ACTIVE", "CANCELLED");

    /** 选课记录动作，全部动作都会写入 enrollment_records 并进入审计账本。 */
    public static final Set<String> ENROLLMENT_ACTIONS =
            Set.of("SELECT", "DROP", "ADMIN_ASSIGN", "ADMIN_REMOVE", "AUTO_REFUND");

    /** 选课记录状态。 */
    public static final Set<String> ENROLLMENT_STATUS = Set.of("ACTIVE", "DROPPED");

    /** 学分与选课人数上限，防止单次请求耗尽资源。 */
    public static final int MAX_CREDITS_LIMIT = 40;

    public static final int MAX_BATCH_STUDENTS = 300;

    /** 组织层级，用于统一校验编号格式与上级归属。 */
    public enum Level {
        COLLEGE("学院", "colleges", COLLEGE_PREFIX, 1),
        MAJOR("专业", "majors", MAJOR_PREFIX, 2),
        CLASS("班级", "classes", CLASS_PREFIX, 3);

        public final String label;
        public final String table;
        public final String prefix;
        public final int depth;

        Level(String label, String table, String prefix, int depth) {
            this.label = label;
            this.table = table;
            this.prefix = prefix;
            this.depth = depth;
        }

        public boolean isRoot() {
            return depth == 1;
        }

        public String parentField() {
            return switch (this) {
                case MAJOR -> "college_id";
                case CLASS -> "major_id";
                case COLLEGE -> null;
            };
        }

        public Level parent() {
            return switch (this) {
                case MAJOR -> COLLEGE;
                case CLASS -> MAJOR;
                case COLLEGE -> null;
            };
        }
    }

    /** 按编号前缀判断该编号属于哪一层；前缀不合法时返回 null。 */
    public static Level levelOfCode(String code) {
        if (code == null || code.isBlank()) return null;
        for (Level level : Level.values())
            if (code.startsWith(level.prefix) && code.matches(codePattern(level))) return level;
        return null;
    }

    /** 主键编号格式：单字母前缀 + 2 位号段 + 3 位本级序号，例如 {@code C01001}。 */
    public static String codePattern(Level level) {
        return level.prefix + "\\d{5}";
    }

    /** 判断主键编号是否等于期望层级要求的格式。 */
    public static boolean validCode(Level level, String code) {
        return code != null && code.matches(codePattern(level));
    }

    /**
     * 组织显示编号（{@code code}）的上限：两位数字，从 {@code 01} 开始递增，同层唯一，
     * 且一经生成不可修改。{@code 99} 之后视为用尽。
     */
    public static final int MAX_CODE = 99;

    /** 显示编号是否合法（两位数字，{@code 01}–{@code 99}）。 */
    public static boolean validCodeValue(String code) {
        return code != null && code.matches("\\d{2}");
    }

    /** 把 1–99 的序号格式化成两位显示编号。 */
    public static String formatCode(int sequence) {
        return String.format("%02d", sequence);
    }

    /** 解析两位显示编号为序号；非法或缺失返回 0。 */
    public static int parseCode(Object code) {
        if (code == null) return 0;
        String text = code.toString().strip();
        if (!validCodeValue(text)) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public record User(
            String id,
            String username,
            String name,
            String role,
            Set<String> permissions,
            int version,
            String collegeId,
            String majorId,
            String classId) {

        /** 兼容旧调用点：无组织归属的用户。 */
        public User(
                String id, String username, String name, String role, Set<String> permissions, int version) {
            this(id, username, name, role, permissions, version, null, null, null);
        }

        public void require(String permission) {
            ApiException.require(permissions.contains(permission), 403, "没有此操作权限");
        }

        /** 是否持有给定权限之一；用于“教务、管理”等多入口判断。 */
        public boolean any(String... candidates) {
            for (String candidate : candidates) if (permissions.contains(candidate)) return true;
            return false;
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

    /** 可空文本：缺失或空白返回 null，超长仍然拒绝。 */
    public static String optionalText(Map<String, Object> m, String key, int max) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) return null;
        ApiException.require(v.toString().length() <= max, 400, "字段过长：" + key);
        return v.toString().strip();
    }

    /** 前端布尔字段可能是 true/false、1/0 或 "true"/"false"，统一解析。 */
    public static boolean flag(Object value, boolean fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        if (value instanceof Boolean b) return b;
        String text = value.toString().strip();
        ApiException.require(
                text.equalsIgnoreCase("true")
                        || text.equalsIgnoreCase("false")
                        || text.equals("1")
                        || text.equals("0"),
                400,
                "布尔字段取值无效：" + text);
        return text.equalsIgnoreCase("true") || text.equals("1");
    }

    /** 把布尔字段转成数据库里的 0/1。 */
    public static int flagInt(Object value, boolean fallback) {
        return flag(value, fallback) ? 1 : 0;
    }

    public static Map<String, Object> publicUser(Map<String, Object> user) {
        var copy = new LinkedHashMap<>(user);
        copy.remove("password");
        return copy;
    }

    public static Map<String, Object> grade(Map<String, Object> row) {
        var copy = new LinkedHashMap<>(row);
        Object payload = copy.remove("payload");
        copy.put("scores", object(payload));
        return copy;
    }


    /**
     * 加权总评。
     *
     * <p>系数表（{@code weights}）若缺少某一项，按 <b>0 权重</b> 处理而不是抛异常：正常路径下
     * {@code CourseService.weights()} 强制要求六项齐全，但历史数据或直接构造的课程行可能不全，
     * 此时「缺项」只应让该分项不参与计算，不应把整门课判成「无成绩」——后者会让重修判定静默失效
     * （挂科学生不被识别为重修），且异常被上层 catch 吞掉后完全无迹可循。
     */
    public static Double total(Map<String, Object> scores, Map<String, Object> weights) {
        double sum = 0;
        for (String key : COMPONENTS) {
            Object raw = weights.get(key);
            double w = raw == null ? 0 : number(raw);
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
