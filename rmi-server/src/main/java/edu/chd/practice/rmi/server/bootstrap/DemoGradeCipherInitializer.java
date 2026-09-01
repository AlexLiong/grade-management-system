package edu.chd.practice.rmi.server.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.GradeCipherCodec;
import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;
import edu.chd.practice.rmi.server.security.SecretMaterialProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Profile("!prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DemoGradeCipherInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecretMaterialProvider secrets;
    private final ObjectMapper objectMapper;

    public DemoGradeCipherInitializer(JdbcTemplate jdbc, SecretMaterialProvider secrets,
                                      ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, DemoGrade> scores = new LinkedHashMap<>();
        scores.put("1", grade(components("DAILY", "90", "LAB", "90", "FINAL", "94"), "92", null, null));
        scores.put("2", grade(components("DAILY", "85", "LAB", "85", "FINAL", "89"), "87", null, null));
        scores.put("3", grade(components("DAILY", "76", "LAB", "80", "FINAL", "78.4"), "78", null, null));
        scores.put("4", grade(components("HOMEWORK", "90", "LAB", "90", "FINAL", "90"), "90", null, null));
        scores.put("5", grade(components("HOMEWORK", "85", "LAB", "85", "FINAL", "85"), "85", null, null));
        scores.put("6", grade(components("HOMEWORK", "60", "LAB", "50", "FINAL", "56"),
                "55", "67", "SUBMITTED"));
        scores.put("7", grade(components("LAB", "95", "PROJECT", "95", "FINAL", "95"), "95", null, null));
        scores.put("8", grade(components("LAB", "90", "PROJECT", "85", "FINAL", "85"), "87", null, null));
        scores.put("9", grade(components("LAB", "30", "PROJECT", "85", "FINAL", "75"), "59", null, null));
        scores.put("10", grade(components("LAB", "90", "PROJECT", "90", "FINAL", "92.5"), "91", null, null));
        scores.put("11", grade(components("LAB", "85", "PROJECT", "85", "FINAL", "85"), "85", null, null));
        scores.put("12", grade(components("LAB", "42", "PROJECT", "38", "FINAL", "52.5"),
                "45", "58", "SUBMITTED"));
        scores.put("13", grade(components("DAILY", "93", "LAB", "93", "FINAL", "93"), "93", null, null));
        scores.put("14", grade(components("DAILY", "84", "LAB", "84", "FINAL", "84"), "84", null, null));
        scores.put("15", grade(components("PAPER", "90", "PROJECT", "85", "FINAL", "90"), "88", null, null));
        scores.put("16", grade(components("PAPER", "80", "PROJECT", "80", "FINAL", "82.5"), "81", null, null));
        scores.put("17", grade(components("PAPER", "90", "PROJECT", "90", "FINAL", "90"), "90", null, null));
        scores.put("18", grade(components("DAILY", "90", "LAB", "85", "FINAL", "90"), "89", null, null));
        scores.put("19", grade(components("DAILY", "90", "LAB", "35", "FINAL", "88"), "78", null, null));
        scores.put("20", grade(components("DAILY", "60", "LAB", "55", "FINAL", "56"),
                "57", "68", "SUBMITTED"));
        scores.put("21", grade(components("DAILY", "90", "LAB", "90", "FINAL", "92"), "91", null, null));
        scores.put("22", grade(components("DAILY", "84", "LAB", "84", "FINAL", "84"), "84", null, null));
        scores.put("23", grade(components("DAILY", "76", "LAB", "76", "FINAL", "76"), "76", null, null));
        scores.forEach(this::encryptIfUninitialized);
    }

    private void encryptIfUninitialized(String gradeId, DemoGrade grade) {
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grades WHERE id=? AND score_ciphertext=''", Long.class, gradeId);
        if (pending == null || pending == 0) return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(grade);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize demo grade " + gradeId, exception);
        }
        EncryptedGradePayload encrypted = GradeCipherCodec.encrypt(
                secrets.gradeKey(), gradeId, payload);
        jdbc.update("""
                UPDATE grades SET score_ciphertext=?, score_nonce=?, score_integrity=?
                WHERE id=? AND score_ciphertext=''
                """, encrypted.getCiphertext(), encrypted.getNonce(), encrypted.getIntegrity(), gradeId);
    }

    private static DemoGrade grade(Map<String, BigDecimal> components, String regular,
                                   String makeupRaw, String makeupStatus) {
        BigDecimal regularScore = new BigDecimal(regular).setScale(2);
        BigDecimal raw = makeupRaw == null ? null : new BigDecimal(makeupRaw).setScale(2);
        BigDecimal effective = raw == null ? null : raw.min(BigDecimal.valueOf(60)).setScale(2);
        return new DemoGrade(components, regularScore, raw, effective,
                effective == null ? regularScore : effective, makeupStatus);
    }

    private static Map<String, BigDecimal> components(String firstCode, String firstScore,
                                                       String secondCode, String secondScore,
                                                       String thirdCode, String thirdScore) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put(firstCode, new BigDecimal(firstScore).setScale(2));
        values.put(secondCode, new BigDecimal(secondScore).setScale(2));
        values.put(thirdCode, new BigDecimal(thirdScore).setScale(2));
        return values;
    }

    private record DemoGrade(Map<String, BigDecimal> componentScores, BigDecimal regularScore,
                             BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                             BigDecimal finalScore, String makeupStatus) { }
}
