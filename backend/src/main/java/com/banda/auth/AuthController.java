package com.banda.auth;

import com.banda.auth.dto.ActivateAccountRequest;
import com.banda.auth.dto.CompletePasswordResetRequest;
import com.banda.auth.dto.CurrentUserResponse;
import com.banda.auth.dto.LoginRequest;
import com.banda.auth.dto.LoginResponse;
import com.banda.auth.dto.RequestPasswordResetRequest;
import com.banda.security.SecurityConstants;
import com.banda.users.UserAccount;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final Duration accessTokenTtl;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                           @Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl,
                           @Value("${app.security.cookie-secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.accessTokenTtl = accessTokenTtl;
        this.cookieSecure = cookieSecure;
    }

    /**
     * Bootstrap endpoint for the SPA: a plain GET that the double-submit CSRF filter
     * chain turns into a fresh {@code XSRF-TOKEN} cookie, so a not-yet-authenticated
     * client (e.g. on the login page) has a CSRF token to echo back on its first POST.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf() {
        // No-op body; the CsrfCookieFilter does the actual work for this request.
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal UserAccount user) {
        return CurrentUserResponse.from(user);
    }

    @PostMapping("/activate")
    public ResponseEntity<Void> activate(@Valid @RequestBody ActivateAccountRequest request) {
        authService.activate(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request.email(), request.password());
        response.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie(result.jwt(), accessTokenTtl).toString());
        return ResponseEntity.ok(new LoginResponse(result.email(), result.role()));
    }

    @PostMapping("/password-reset/complete")
    public ResponseEntity<Void> completePasswordReset(@Valid @RequestBody CompletePasswordResetRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody RequestPasswordResetRequest request) {
        try {
            authService.requestPasswordReset(request.email());
        } catch (PasswordResetDeliveryException ignored) {
            // SMTP state must not turn this endpoint into an account-enumeration oracle.
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserAccount user, HttpServletResponse response) {
        authService.logout(user);
        response.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie("", Duration.ZERO).toString());
        return ResponseEntity.ok().build();
    }

    private ResponseCookie accessTokenCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SecurityConstants.ACCESS_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }
}
