package edu.chd.practice.web.security;

import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {
    @Test
    void blocksOneAddressEvenWhenUsernamesRotate() {
        LoginRateLimiter limiter = limiter();
        limiter.failed("192.0.2.10", "first");
        limiter.failed("192.0.2.10", "second");

        assertThatThrownBy(() -> limiter.check("192.0.2.10", "third"))
                .isInstanceOf(ApiException.class).hasMessageContaining("登录失败次数过多");
    }

    @Test
    void blocksOneUsernameEvenWhenAddressesRotate() {
        LoginRateLimiter limiter = limiter();
        limiter.failed("192.0.2.11", "TargetUser");
        limiter.failed("192.0.2.12", " targetuser ");

        assertThatThrownBy(() -> limiter.check("192.0.2.13", "TARGETUSER"))
                .isInstanceOf(ApiException.class).hasMessageContaining("登录失败次数过多");
    }

    @Test
    void successfulLoginClearsUserCounterButNotAddressCounter() {
        LoginRateLimiter limiter = limiter();
        limiter.failed("192.0.2.20", "valid-user");
        limiter.succeeded("192.0.2.20", "valid-user");
        assertThatCode(() -> limiter.check("192.0.2.21", "valid-user")).doesNotThrowAnyException();

        limiter.failed("192.0.2.20", "another-user");
        assertThatThrownBy(() -> limiter.check("192.0.2.20", "new-user"))
                .isInstanceOf(ApiException.class);
    }

    private LoginRateLimiter limiter() {
        AppProperties.Security security = new AppProperties.Security(
                null, null, Duration.ofHours(1), 2, 10);
        return new LoginRateLimiter(new AppProperties(security,
                new AppProperties.Crypto(null, null), List.of(), false));
    }
}
