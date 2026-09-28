package com.vcut.api.auth.presentation;

import com.vcut.api.auth.application.AuthApplicationService;
import com.vcut.api.auth.application.AuthResult;
import com.vcut.api.auth.infrastructure.AuthProperties;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  static final String REFRESH_COOKIE = "vcut_refresh_token";

  private final AuthApplicationService authApplicationService;
  private final AuthProperties authProperties;

  public AuthController(
      AuthApplicationService authApplicationService, AuthProperties authProperties) {
    this.authApplicationService = authApplicationService;
    this.authProperties = authProperties;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return authenticated(authApplicationService.register(request.email(), request.password()));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return authenticated(authApplicationService.login(request.email(), request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookieRefreshToken) {
    String refreshToken = request == null ? null : request.refreshToken();
    if (refreshToken == null || refreshToken.isBlank()) {
      refreshToken = cookieRefreshToken;
    }
    return authenticated(authApplicationService.refresh(refreshToken));
  }

  @DeleteMapping("/logout")
  public ResponseEntity<Void> logout(
      @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookieRefreshToken) {
    String refreshToken = request == null ? null : request.refreshToken();
    authApplicationService.logout(
        refreshToken == null || refreshToken.isBlank() ? cookieRefreshToken : refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, deleteRefreshCookie().toString())
        .build();
  }

  private ResponseEntity<AuthResponse> authenticated(AuthResult result) {
    return ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
        .body(new AuthResponse(result.accessToken(), result.accessTokenExpiresInSeconds()));
  }

  private ResponseCookie refreshCookie(String value) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(authProperties.refreshCookieSecure())
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(authProperties.refreshTokenTtl())
        .build();
  }

  private ResponseCookie deleteRefreshCookie() {
    return ResponseCookie.from(REFRESH_COOKIE, "")
        .httpOnly(true)
        .secure(authProperties.refreshCookieSecure())
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(Duration.ZERO)
        .build();
  }
}
