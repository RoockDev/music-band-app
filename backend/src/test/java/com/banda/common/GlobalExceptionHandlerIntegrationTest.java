package com.banda.common;

import com.banda.security.JwtService;
import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves DB unavailability (any {@link org.springframework.dao.DataAccessException}) never
 * leaks a stack trace or raw exception message to the client — it must surface as a
 * generic 503, logged at ERROR server-side.
 */
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerIntegrationTest.FailingControllerConfig.class)
class GlobalExceptionHandlerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void dataAccessExceptionIsTranslatedToGeneric503WithoutLeakingDetails() throws Exception {
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("db-failure-test@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now()));
        String token = jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name());

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            String body = mockMvc.perform(get("/api/test/db-failure")
                            .cookie(new Cookie("ACCESS_TOKEN", token)))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).doesNotContain("boom-internal-detail");
            assertThat(body).doesNotContain("QueryTimeoutException");

            boolean errorLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getLoggerName().equals(GlobalExceptionHandler.class.getName()));
            assertThat(errorLogged).isTrue();
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @TestConfiguration
    static class FailingControllerConfig {

        @RestController
        static class FailingController {

            @GetMapping("/api/test/db-failure")
            public String fail() {
                throw new QueryTimeoutException("boom-internal-detail");
            }
        }
    }
}
