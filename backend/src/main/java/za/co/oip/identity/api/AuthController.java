package za.co.oip.identity.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import za.co.oip.common.error.Exceptions.AuthenticationFailedException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.identity.AuthService;
import za.co.oip.identity.AuthService.ClientInfo;
import za.co.oip.identity.api.AuthDtos.AuthResponse;
import za.co.oip.identity.api.AuthDtos.LoginRequest;
import za.co.oip.identity.api.AuthDtos.RefreshRequest;
import za.co.oip.identity.api.AuthDtos.RegisterRequest;

@Tag(name = "Authentication")
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Name of the httpOnly cookie that carries the refresh token for the web app. */
    public static final String REFRESH_COOKIE = "oip_refresh";

    private final AuthService authService;
    private final boolean secureCookie;

    private final za.co.oip.identity.AccountEmailService accountEmails;

    public AuthController(AuthService authService, za.co.oip.identity.AccountEmailService accountEmails,
                          @Value("${oip.security.secure-cookies:true}") boolean secureCookie) {
        this.authService = authService;
        this.accountEmails = accountEmails;
        this.secureCookie = secureCookie;
    }

    @Operation(summary = "Register a new business account (tenant) and its owner user")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http, HttpServletResponse res) {
        return withCookie(authService.register(request, client(http)), res);
    }

    @Operation(summary = "Sign in with email and password")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse res) {
        return withCookie(authService.login(request, client(http)), res);
    }

    @Operation(summary = "Exchange a refresh token for a new access token (rotates the refresh token)")
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody(required = false) RefreshRequest request, HttpServletRequest http,
                                HttpServletResponse res) {
        String token = request != null && request.refreshToken() != null && !request.refreshToken().isBlank()
                ? request.refreshToken() : cookie(http);
        if (token == null) {
            throw new AuthenticationFailedException("No refresh token");
        }
        return withCookie(authService.refresh(token, client(http)), res);
    }

    @Operation(summary = "E-mail a password reset link. Always returns 202, whether or not the address has an account")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody za.co.oip.identity.api.AuthDtos.ForgotPasswordRequest request) {
        accountEmails.requestPasswordReset(request.email());
    }

    @Operation(summary = "What an e-mailed link is for (reset, invitation or verification), without using it")
    @PostMapping("/token-info")
    public za.co.oip.identity.api.AuthDtos.TokenInfo tokenInfo(
            @Valid @RequestBody za.co.oip.identity.api.AuthDtos.TokenRequest request) {
        return accountEmails.describe(request.token());
    }

    @Operation(summary = "Set a password from a reset or invitation link; signs out all existing sessions")
    @PostMapping("/set-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@Valid @RequestBody za.co.oip.identity.api.AuthDtos.SetPasswordRequest request) {
        accountEmails.setPassword(request.token(), request.password());
    }

    @Operation(summary = "Confirm an e-mail address from a verification link")
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody za.co.oip.identity.api.AuthDtos.TokenRequest request) {
        accountEmails.verifyEmail(request.token());
    }

    @Operation(summary = "Send a new verification link to the signed-in user")
    @PostMapping("/verify-email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification() {
        accountEmails.sendVerification(za.co.oip.security.CurrentUser.require().userId());
    }

    @Operation(summary = "Revoke a refresh token")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) RefreshRequest request, HttpServletRequest http, HttpServletResponse res) {
        String token = request != null && request.refreshToken() != null ? request.refreshToken() : cookie(http);
        if (token != null) {
            authService.logout(token);
        }
        res.addHeader(HttpHeaders.SET_COOKIE, cookieValue("", Duration.ZERO));
    }

    private AuthResponse withCookie(AuthResponse r, HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookieValue(r.refreshToken(),
                Duration.between(Instant.now(), r.refreshTokenExpiresAt())));
        return r;
    }

    private String cookieValue(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build().toString();
    }

    private static String cookie(HttpServletRequest http) {
        if (http.getCookies() == null) return null;
        for (Cookie c : http.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(http.getHeader(HttpHeaders.USER_AGENT), http.getRemoteAddr());
    }
}
