package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
    private final RemoteRepository repo;
    private final OrganizationService organizations;

    public AdminService(RemoteRepository repo, OrganizationService organizations) {
        this.repo = repo;
        this.organizations = organizations;
    }

    /**
     * 人员列表。返回的数据把学院、专业、班级编号翻译成名称：前端按名称展示与操作，
     * {@code department} 作为展示用的派生字段，学生为“专业·班级”，教师为“学院·专业”。
     */
    public List<Map<String, Object>> users(Models.User u) {
        u.require("USER_ADMIN");
        var all = repo.find("users", Map.of());
        var colleges = organizations.names(Models.Level.COLLEGE, ids(all, "college_id"));
        var majors = organizations.names(Models.Level.MAJOR, ids(all, "major_id"));
        var classes = organizations.names(Models.Level.CLASS, ids(all, "class_id"));
        var result = new ArrayList<Map<String, Object>>();
        for (var row : all) {
            var copy = Models.publicUser(row);
            String collegeId = text(row.get("college_id"));
            String majorId = text(row.get("major_id"));
            String classId = text(row.get("class_id"));
            copy.put("collegeName", colleges.get(collegeId));
            copy.put("majorName", majors.get(majorId));
            copy.put("className", classes.get(classId));
            copy.put("department", department(row.get("role"), colleges.get(collegeId), majors.get(majorId), classes.get(classId)));
            result.add(copy);
        }
        return result;
    }

    /** 教师名录：课程维护与选课范围都按学院、专业筛选教师。 */
    public List<Map<String, Object>> teachers(Models.User u) {
        u.require("USER_ADMIN");
        var all = repo.find("users", Map.of("role", "TEACHER"));
        var colleges = organizations.names(Models.Level.COLLEGE, ids(all, "college_id"));
        var majors = organizations.names(Models.Level.MAJOR, ids(all, "major_id"));
        var result = new ArrayList<Map<String, Object>>();
        for (var row : all) {
            var copy = new LinkedHashMap<String, Object>();
            copy.put("id", row.get("id"));
            copy.put("name", row.get("name"));
            copy.put("username", row.get("username"));
            copy.put("enabled", row.get("enabled"));
            copy.put("collegeName", colleges.get(text(row.get("college_id"))));
            copy.put("majorName", majors.get(text(row.get("major_id"))));
            result.add(copy);
        }
        return result;
    }

    /** 展示用归属描述；不写入数据库，避免与组织表产生第二份真相。 */
    public static String department(Object role, String college, String major, String klazz) {
        if (role == null) return "";
        if (role.toString().equals("STUDENT"))
            return join(major == null ? college : major, klazz);
        return join(college, major);
    }

    private static String join(String first, String second) {
        if (first == null) return second == null ? "" : second;
        if (second == null) return first;
        return first + "·" + second;
    }

    private static List<String> ids(List<Map<String, Object>> rows, String field) {
        var list = new ArrayList<String>();
        for (var row : rows) list.add(text(row.get(field)));
        return list;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /** 取第一个非空字段，便于同时接受“编号”和“名称”两种前端写法。 */
    private static Object first(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null && !value.toString().isBlank()) return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public void saveUser(Models.User u, Map<String, Object> b) {
        u.require("USER_ADMIN");
        boolean create = b.get("id") == null;
        String id = create ? UUID.randomUUID().toString() : Models.text(b, "id", 100),
                role = Models.text(b, "role", 20);
        ApiException.require(Models.PERMISSIONS.containsKey(role), 400, "角色无效");
        var perms = new TreeSet<>((List<String>) b.get("permissions"));
        ApiException.require(Models.PERMISSIONS.get(role).containsAll(perms), 400, "权限不属于该角色");
        var username = Models.text(b, "username", 60);
        ApiException.require(username.matches("[A-Za-z0-9_.-]{3,60}"), 400, "账号只支持字母、数字、点、下划线、连字符");
        int enabled = Models.integer(b.get("enabled"));
        ApiException.require(enabled == 0 || enabled == 1, 400, "账号状态错误");
        // 组织归属：学院、专业、班级全部按名称解析成编号，并按层级校验一致性。
        String collegeId = organizations.resolveOwn(Models.Level.COLLEGE, first(b, "collegeId", "collegeName", "college"));
        String majorId = organizations.resolveOwn(Models.Level.MAJOR, first(b, "majorId", "majorName", "major"));
        String classId = organizations.resolveOwn(Models.Level.CLASS, first(b, "classId", "className", "class"));
        // 需求：管理员角色不归属任何组织，因此请求体里带了组织也必须拒绝；
        // 保存时管理员的三级组织字段一律写空串（库里用空串表示「不设置」）。
        if (role.equals("ADMIN")) {
            ApiException.require(
                    collegeId == null && majorId == null && classId == null,
                    400,
                    "管理员不归属学院、专业或班级");
        } else {
            ApiException.require(collegeId != null, 400, "必须指定所属学院");
        }
        if (role.equals("TEACHER")) ApiException.require(majorId != null, 400, "教师必须指定所属专业");
        if (role.equals("STUDENT")) {
            ApiException.require(majorId != null, 400, "学生必须指定所属专业");
            ApiException.require(classId != null, 400, "学生必须指定所属班级");
        }
        if (role.equals("ADMIN")) {
            collegeId = null;
            majorId = null;
            classId = null;
        }
        if (majorId != null)
            ApiException.require(
                    collegeId != null
                            && collegeId.equals(organizations.requireOrganization(Models.Level.MAJOR, majorId).get("college_id")),
                    400,
                    "所选专业不属于该学院");
        if (classId != null) {
            var klazz = organizations.requireOrganization(Models.Level.CLASS, classId);
            ApiException.require(majorId != null && majorId.equals(klazz.get("major_id")), 400, "所选班级不属于该专业");
            ApiException.require(collegeId != null && collegeId.equals(klazz.get("college_id")), 400, "所选班级不属于该学院");
        }
        // 展示用归属描述在服务端算好，前端不需要拼接组织层级。
        String collegeName =
                collegeId == null
                        ? null
                        : organizations.requireOrganization(Models.Level.COLLEGE, collegeId).get("name").toString();
        String majorName =
                majorId == null
                        ? null
                        : organizations.requireOrganization(Models.Level.MAJOR, majorId).get("name").toString();
        String className =
                classId == null
                        ? null
                        : OrganizationService.displayName(
                                Models.Level.CLASS, organizations.requireOrganization(Models.Level.CLASS, classId));
        var v =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "username",
                                username,
                                "name",
                                Models.text(b, "name", 80),
                                "role",
                                role,
                                "permissions",
                                String.join(",", perms),
                                "department",
                                AdminService.department(role, collegeName, majorName, className),
                                "college_id",
                                collegeId == null ? "" : collegeId,
                                "major_id",
                                majorId == null ? "" : majorId,
                                "class_id",
                                classId == null ? "" : classId,
                                "enabled",
                                enabled));
        List<Protocol.Operation> ops = new ArrayList<>();
        if (create) {
            String password = Models.text(b, "password", 128);
            demoPassword(username).ifPresentOrElse(AuthService::demoValidatePassword, () -> AuthService.validatePassword(password));
            v.put("id", id);
            v.put("password", AuthService.PASSWORDS.encode(password));
            v.put("version", 0);
            ops.add(insert("users", v));
        } else {
            var old = repo.one("users", id);
            ApiException.require(old.get("role").equals(role), 400, "已有账号不可变更角色，请新建对应角色账号");
            if (id.equals(u.id()))
                ApiException.require(
                        enabled == 1 && perms.contains("USER_ADMIN"), 400, "不能停用自己或撤销自己的人员管理权限");
            int version = Models.integer(b.get("version"));
            v.put("version", version + 1);
            if (b.get("password") != null && !b.get("password").toString().isBlank()) {
                String password = Models.text(b, "password", 128);
                demoPassword(username).ifPresentOrElse(AuthService::demoValidatePassword, () -> AuthService.validatePassword(password));
                v.put("password", AuthService.PASSWORDS.encode(password));
            }
            ops.add(update("users", id, v, version));
            repo.find("sessions", Map.of("user_id", id))
                    .forEach(s -> ops.add(delete("sessions", s.get("id").toString(), null)));
        }
        repo.mutate(ops, u.id(), "USER_SAVE", id);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> integrity(Models.User u) {
        u.require("AUDIT");
        var ledger = repo.ledger();
        Map<String, Map<String, Object>> originals = new LinkedHashMap<>();
        for (var event : (List<Map<String, Object>>) ledger.get("events"))
            for (var change : (List<Map<String, Object>>) event.get("changes"))
                if (change.get("table").equals("grades"))
                    originals.put(change.get("id").toString(), (Map<String, Object>) change.get("after"));
        List<Map<String, Object>> issues = new ArrayList<>();
        int checked = 0;
        for (var entry : originals.entrySet()) {
            try {
                var current = repo.find("grades", Map.of("id", entry.getKey()));
                var expected = entry.getValue();
                if (expected.isEmpty()) {
                    if (!current.isEmpty())
                        issues.add(
                                Map.of(
                                        "id",
                                        entry.getKey(),
                                        "issue",
                                        "DELETED_RECORD_REAPPEARED",
                                        "original",
                                        expected));
                } else {
                    expected.computeIfPresent("version", (k, v) -> v.toString());
                    if (current.isEmpty() || !current.get(0).equals(expected)) {
                        issues.add(
                                Map.of(
                                        "id",
                                        entry.getKey(),
                                        "issue",
                                        current.isEmpty() ? "MISSING" : "MISMATCH",
                                        "original",
                                        Models.grade(expected)
                                )
                        );
                    }
                }
            } catch (ApiException e) {
                if (e.status != 409) throw e;
                issues.add(
                        Map.of(
                                "id",
                                entry.getKey(),
                                "issue",
                                "CIPHERTEXT_TAMPERED",
                                "original",
                                entry.getValue().isEmpty() ? Map.of() : Models.grade(entry.getValue())));
            }
            checked++;
        }
        // Query identifiers without decrypting payloads to detect rows fabricated directly in the
        // database.
        var rpc = new RpcClient("business");
        var ids =
                rpc.post(
                        "data",
                        "/internal/select",
                        new Protocol.Selection("grades", List.of("id"), Map.of(), "id", 0, 10000),
                        String[][].class);
        for (var row : ids)
            if (!originals.containsKey(row[0]))
                issues.add(Map.of("id", row[0], "issue", "UNAUDITED_ROW", "original", Map.of()));
        return Map.of(
                "verified",
                issues.isEmpty(),
                "checked",
                checked,
                "issues",
                issues,
                "head",
                ledger.get("head"),
                "outbox",
                repo.status());
    }

    public void review(Models.User u, Map<String, Object> b) {
        u.require("AUDIT");
        repo.securityEvent(
                u.id(),
                "ADMIN_REVIEW",
                Models.text(b, "resource", 200) + ":" + Models.text(b, "comment", 1000));
    }

    /** 与 {@code DemoInitializer} 的种子账号保持一致：这些账号使用演示密码，不做复杂度校验。 */
    private static final Set<String> DEMO_USERNAMES =
            Set.of(
                    // 管理员（不归属任何组织）
                    "admin", "jw001", "jw002",
                    // 教师
                    "t1101", "t1102", "t1201", "t1202", "t2101", "t2201", "t2202",
                    "t3101", "t3201", "t4101", "t4201",
                    // 2023 级学生
                    "20231530", "20231531", "20231532", "20231533", "20231534", "20231535",
                    "20231536", "20231537", "20231538", "20231539", "20231540", "20231541",
                    "20231542", "20231543", "20231544", "20231545", "20231546", "20231547",
                    "20231548", "20231549", "20231550", "20231551", "20231552", "20231553",
                    // 2024 级学生
                    "20241530", "20241531", "20241532", "20241533", "20241534", "20241535",
                    "20241536", "20241537", "20241538", "20241540", "20241541", "20241542",
                    "20241543", "20241544", "20241545", "20241546", "20241547", "20241548",
                    "20241549", "20241550", "20241551", "20241552", "20241553", "20241554",
                    // 待分班学生
                    "20241601", "20241602", "20241603");

    static Optional<String> demoPassword(String username) {
        return DEMO_USERNAMES.contains(username) ? Optional.of(username) : Optional.empty();
    }
}
