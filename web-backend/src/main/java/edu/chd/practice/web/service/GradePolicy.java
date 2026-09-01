package edu.chd.practice.web.service;

import edu.chd.practice.web.error.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class GradePolicy {
    private GradePolicy() {
    }

    public static BigDecimal makeupEffective(BigDecimal regularScore, BigDecimal makeupRawScore) {
        if (regularScore == null || makeupRawScore == null) {
            throw new IllegalArgumentException("Regular and makeup scores are required");
        }
        if (regularScore.compareTo(BigDecimal.valueOf(60)) >= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_NOT_ALLOWED", "正考已及格，不允许录入补考成绩");
        }
        if (makeupRawScore.signum() < 0 || makeupRawScore.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MAKEUP_SCORE_INVALID", "补考卷面分必须在 0 到 100 之间");
        }
        return makeupRawScore.min(BigDecimal.valueOf(60)).setScale(2, RoundingMode.HALF_UP);
    }
}
