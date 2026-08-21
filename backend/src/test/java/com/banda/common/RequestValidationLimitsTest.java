package com.banda.common;

import com.banda.auth.dto.ActivateAccountRequest;
import com.banda.auth.dto.CompletePasswordResetRequest;
import com.banda.auth.dto.LoginRequest;
import com.banda.events.dto.CreateEventRequest;
import com.banda.events.dto.UpdateEventRequest;
import com.banda.publicsite.dto.CreateNewsPostRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationLimitsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsEventMetadataBeyondDatabaseColumnLimits() {
        CreateEventRequest create = new CreateEventRequest(
                "t".repeat(256), "d".repeat(256), "l".repeat(256), Instant.now(), false, false,
                List.of(), List.of());
        UpdateEventRequest update = new UpdateEventRequest(
                "Title", null, "l".repeat(256), Instant.now(), false, false,
                List.of(), List.of(), 0L);

        assertThat(validator.validate(create))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title", "description", "location");
        assertThat(validator.validate(update))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("location");
    }

    @Test
    void rejectsOversizedNewsAndAuthenticationPayloads() {
        assertThat(validator.validate(new CreateNewsPostRequest("Title", "b".repeat(10001))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("body");
        assertThat(validator.validate(new LoginRequest("user@example.com", "p".repeat(65))))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("password");
        assertThat(validator.validate(new ActivateAccountRequest("t".repeat(256), "Password1")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("token");
        assertThat(validator.validate(new CompletePasswordResetRequest("t".repeat(256), "Password1")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("token");
    }
}
