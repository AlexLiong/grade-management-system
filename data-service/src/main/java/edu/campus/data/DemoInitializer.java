package edu.campus.data;

import edu.campus.common.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示数据初始化。data-service 启动时，若 users 表为空则自动写入演示数据。
 */
@Component
@Order(100)
public class DemoInitializer implements ApplicationRunner {

    private static final String DEFAULT_PASSWORD = "passwd";
    public static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    @Value("${campus.DATA_KEY}")
    public String KEY;
    @Autowired
    private JdbcTemplate jdbc;
    private final RpcClient audit = new RpcClient("data");

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("DemoInitializer  called");
        try {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
            if (count != null && count > 0) {
                System.out.println("[DemoInitializer] Database already has data (" + count + " users), skipping init.");
                return;
            }
        } catch (Exception e) {
            System.out.println("[DemoInitializer] users table not found or empty: " + e.getMessage());
        }

        System.out.println("[DemoInitializer] Creating tables and inserting demo data...");
        try {
            createTables();
            insertUsers();
            insertCourses();
            insertEnrollmentsAndGrades();
            seedAuditLedger();
            System.out.println("[DemoInitializer] Demo initialized: 15 users, 5 courses, 48 enrollments, 36 grades.");
        } catch (Exception e) {
            System.err.println("[DemoInitializer] Failed to insert demo data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() {
        String[][] tables = {
                {"users", "id VARCHAR(100) PRIMARY KEY,username VARCHAR(100),password VARCHAR(200),name VARCHAR(100),role VARCHAR(20),permissions VARCHAR(200),department VARCHAR(100),enabled INTEGER,version INTEGER"},
                {"courses", "id VARCHAR(100) PRIMARY KEY,code VARCHAR(100),name VARCHAR(200),term VARCHAR(20),teacher_id VARCHAR(100),credits DECIMAL(10,2),weights CLOB,version INTEGER"},
                {"enrollments", "id VARCHAR(100) PRIMARY KEY,course_id VARCHAR(100),student_id VARCHAR(100)"},
                {"grades", "id VARCHAR(100) PRIMARY KEY,course_id VARCHAR(100),student_id VARCHAR(100),payload CLOB,state VARCHAR(20),version INTEGER"},
                {"analyses", "id VARCHAR(100) PRIMARY KEY,course_id VARCHAR(100),content CLOB,version INTEGER"},
                {"sessions", "id VARCHAR(100) PRIMARY KEY,user_id VARCHAR(100),csrf VARCHAR(100),expires VARCHAR(50)"},
                {"audits", "id VARCHAR(100) PRIMARY KEY,payload CLOB,delivered INTEGER"},
                {"login_limits", "id VARCHAR(100) PRIMARY KEY,failures INTEGER,locked_until VARCHAR(50)"},
        };
        for (String[] t : tables) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + t[0] + " (" + t[1] + ")");
        }
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_enrollment_unique ON enrollments(course_id,student_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_grade_unique ON grades(course_id,student_id)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_analysis_unique ON analyses(course_id)");
    }

    private void insertUsers() {
        String sql = "INSERT INTO users(id,username,password,name,role,permissions,department,enabled,version) VALUES(?,?,?,?,?,?,?,?,?)";
        List<Object[]> users = Arrays.asList(
                new Object[]{"admin", "admin", PASSWORDS.encode(DEFAULT_PASSWORD), "教务管理员", "ADMIN", "GRADE_ADMIN,USER_ADMIN,AUDIT", "信息工程学院", 1, 0},
                new Object[]{"t1101", "t1101", PASSWORDS.encode(DEFAULT_PASSWORD), "陈老师", "TEACHER", "QUERY,PREDICT,ENTRY,MAINTAIN", "信息工程学院", 1, 0},
                new Object[]{"t1102", "t1102", PASSWORDS.encode(DEFAULT_PASSWORD), "李老师", "TEACHER", "QUERY,PREDICT,ENTRY,MAINTAIN", "信息工程学院", 1, 0},
                new Object[]{"20231530", "20231530", PASSWORDS.encode(DEFAULT_PASSWORD), "林知夏", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231531", "20231531", PASSWORDS.encode(DEFAULT_PASSWORD), "周予安", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231532", "20231532", PASSWORDS.encode(DEFAULT_PASSWORD), "陈嘉宁", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231533", "20231533", PASSWORDS.encode(DEFAULT_PASSWORD), "李明远", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231534", "20231534", PASSWORDS.encode(DEFAULT_PASSWORD), "王思齐", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231535", "20231535", PASSWORDS.encode(DEFAULT_PASSWORD), "张书涵", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231536", "20231536", PASSWORDS.encode(DEFAULT_PASSWORD), "赵一诺", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231537", "20231537", PASSWORDS.encode(DEFAULT_PASSWORD), "刘景行", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231538", "20231538", PASSWORDS.encode(DEFAULT_PASSWORD), "孙若溪", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231539", "20231539", PASSWORDS.encode(DEFAULT_PASSWORD), "吴星野", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231540", "20231540", PASSWORDS.encode(DEFAULT_PASSWORD), "郑以宁", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0},
                new Object[]{"20231541", "20231541", PASSWORDS.encode(DEFAULT_PASSWORD), "何雨晴", "STUDENT", "QUERY,PREDICT", "软件工程 2023 级", 1, 0}
        );
        jdbc.batchUpdate(sql, users);
    }

    private void insertCourses() {
        String sql = "INSERT INTO courses(id,code,name,term,teacher_id,credits,weights,version) VALUES(?,?,?,?,?,?,?,?)";
        String weights = "{\"regular\":30,\"attendance\":0,\"homework\":0,\"lab\":20,\"midterm\":0,\"finalExam\":50}";
        List<Object[]> courses = Arrays.asList(
                new Object[]{"net-2023", "CS301", "网络软件与安全", "2023-1", "t1101", 3.0, weights, 0},
                new Object[]{"net-2024", "CS301", "网络软件与安全", "2024-1", "t1101", 3.0, weights, 0},
                new Object[]{"net-2025", "CS301", "网络软件与安全", "2025-1", "t1101", 3.0, weights, 0},
                new Object[]{"net-2026", "CS301", "网络软件与安全", "2026-1", "t1101", 3.0, weights, 0},
                new Object[]{"db-2026", "CS302", "数据库系统", "2026-1", "t1102", 4.0, weights, 0}
        );
        jdbc.batchUpdate(sql, courses);
    }

    private void insertEnrollmentsAndGrades() {
        String[] students = {"20231530", "20231531", "20231532", "20231533", "20231534", "20231535",
                "20231536", "20231537", "20231538", "20231539", "20231540", "20231541"};
        String[][] courses = {{"net-2023", "2023-1"}, {"net-2024", "2024-1"}, {"net-2025", "2025-1"}, {"net-2026", "2026-1"}};
        Random rng = new Random(42);

        String enrollSql = "INSERT INTO enrollments(id,course_id,student_id) VALUES(?,?,?)";
        String gradeSql = "INSERT INTO grades(id,course_id,student_id,payload,state,version) VALUES(?,?,?,?,?,?)";

        for (String[] cc : courses) {
            String cid = cc[0], term = cc[1];
            if (term.equals("2026-1")) continue;

            for (int i = 0; i < students.length; i++) {
                String sid = students[i];
                jdbc.update(enrollSql, cid + "-s" + (i + 1), cid, sid);

                double reg = 35 + rng.nextInt(65);
                double lab = 35 + rng.nextInt(65);
                double exam = Math.min(100, Math.max(0, .6 * reg + .5 * lab - 8 + rng.nextGaussian() * 8));
                String payload;
                if (term.equals("2025-1") && i == 0)
                    payload = "{\"regular\":50.0,\"lab\":50.0,\"finalExam\":66.0,\"makeup\":87.0}";
                else
                    payload = "{\"regular\":" + reg + ",\"lab\":" + lab + ",\"finalExam\":" + (Math.round(exam * 100) / 100.0) + "}";

                // Encrypt payload using same AAD as TransactionService
                String gradeId = cid + "-g" + (i + 1);
                String aad = gradeId + "|" + cid + "|" + sid + "|SUBMITTED|0";
                String encryptedPayload = Crypto.encrypt(Settings.get("DATA_KEY"), aad, payload);
                jdbc.update(gradeSql, gradeId, cid, sid, encryptedPayload, "SUBMITTED", 0);
            }
        }
    }

    private void seedAuditLedger() throws Exception {
        List<String> gradeIds = jdbc.queryForList(
                "SELECT id FROM grades ORDER BY id", String.class);
        if (gradeIds.isEmpty()) {
            System.out.println("[DemoInitializer] No grades to seed into ledger.");
            return;
        }

        // Check if ledger already exists and is valid with current keys
        Path ledgerFile = Settings.root().resolve("ledger/events.jsonl");
        if (Files.exists(ledgerFile) && Files.size(ledgerFile) > 0) {
            if (verifyLedgerWithDemoKeys(ledgerFile)) {
                System.out.println("[DemoInitializer] Ledger already valid, skipping seed.");
                return;
            }
            System.out.println("[DemoInitializer] Ledger exists but keys mismatch, clearing and rebuilding...");
            Files.delete(ledgerFile);
        }

        List<Protocol.AuditEvent> events = new ArrayList<>();
        for (String gid : gradeIds) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM grades WHERE id=?", gid);
            String courseId = (String) row.get("course_id");
            String studentId = (String) row.get("student_id");
            String state = (String) row.get("state");
            int version = row.get("version") == null ? 0 : ((Number) row.get("version")).intValue();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("id", gid);
            after.put("course_id", courseId);
            after.put("student_id", studentId);
            after.put("payload", row.get("payload"));
            after.put("state", state);
            after.put("version", version);
            events.add(new Protocol.AuditEvent(
                    UUID.randomUUID().toString(), "DEMO_SEED", "GRADE_SEED", courseId,
                    Instant.now().toString(),
                    List.of(Map.of("table", "grades", "id", gid, "before", Map.of(), "after", after))));
        }
        audit.post("audit", "/internal/bootstrap-anchored", events, Map.class);
        System.out.println("[DemoInitializer] Ledger bootstrapped with EVM anchors (" + events.size() + " events).");
    }

    /**
     * Verify the ledger file can be read with the current DEMO keys (hash chain + HMAC).
     */
    private boolean verifyLedgerWithDemoKeys(Path ledgerFile) {
        try {
            String prev = "0";
            int count = 0;
            for (String line : Files.readAllLines(ledgerFile)) {
                Map<String, Object> block = Settings.JSON.readValue(line, Map.class);
                String expectedHash = Crypto.hash(
                        block.get("index") + "|" + prev + "|" + block.get("ciphertext"));
                String expectedSig = Crypto.hmac(KEY, expectedHash);
                if (!expectedHash.equals(block.get("hash")) || !expectedSig.equals(block.get("signature"))) {
                    return false;
                }
                prev = expectedHash;
                count++;
            }
            System.out.println("[DemoInitializer] Ledger verified: " + count + " blocks OK.");
            return true;
        } catch (Exception e) {
            return false;
        }
    }



}
