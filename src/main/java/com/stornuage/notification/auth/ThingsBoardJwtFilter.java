package com.stornuage.notification.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Validates the Bearer JWT attached by the mobile app.
 *
 * Token origin: ThingsBoard issues the token when the user logs in via
 * /api/auth/login on ThingsBoard. The app attaches it as
 *   Authorization: Bearer <token>
 * on every call into our APIs.
 *
 * We verify signature with the same signing key ThingsBoard used
 * (TB_JWT_SIGNING_KEY env var). If invalid or expired → 401.
 * If valid → AuthenticatedUser is stored as a request attribute
 * under the key defined in {@link #ATTR_USER}.
 *
 * Paths that do NOT require auth: /actuator/** (health/metrics).
 */
@Slf4j
@Component
public class ThingsBoardJwtFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "authenticatedUser";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REQUIRED_ALG = "HS512";
    private static final int HS512_MIN_KEY_BYTES = 64;

    private final SecretKey signingKey;

    public ThingsBoardJwtFilter(@Value("${auth.thingsboard.jwt-signing-key:}") String jwtSigningKey) {
        if (jwtSigningKey == null || jwtSigningKey.isBlank()) {
            log.error("TB_JWT_SIGNING_KEY not configured — authentication will reject all tokens");
            this.signingKey = null;
            return;
        }
        var keyBytes = jwtSigningKey.getBytes(StandardCharsets.UTF_8);
        // HS512 requires a key of at least 512 bits (64 bytes). A shorter key would cause
        // Keys.hmacShaKeyFor to silently downgrade to HS384/HS256, which would then accept
        // attacker-forged tokens signed with the weaker algorithm.
        if (keyBytes.length < HS512_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "TB_JWT_SIGNING_KEY must be at least " + HS512_MIN_KEY_BYTES
                            + " bytes for HS512; got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(response, "Missing or malformed Authorization header");
            return;
        }

        if (signingKey == null) {
            reject(response, "Service not configured to verify tokens");
            return;
        }

        var token = header.substring(BEARER_PREFIX.length());
        try {
            var jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);

        
            var alg = jws.getHeader().getAlgorithm();
            if (!REQUIRED_ALG.equals(alg)) {
                log.debug("Rejecting JWT with algorithm {}; expected {}", alg, REQUIRED_ALG);
                reject(response, "Unsupported token algorithm");
                return;
            }

            var claims = jws.getPayload();

            var user = new AuthenticatedUser(
                    claims.get("userId", String.class),
                    claims.getSubject(),
                    firstScope(claims),
                    claims.get("customerId", String.class)
            );

            request.setAttribute(ATTR_USER, user);
            chain.doFilter(request, response);
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            reject(response, "Invalid or expired token");
        }
    }

    private String firstScope(io.jsonwebtoken.Claims claims) {
        var raw = claims.get("scopes");
        if (raw instanceof java.util.List<?> list && !list.isEmpty()) {
            return list.get(0).toString();
        }
        if (raw instanceof String s) return s;
        return null;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
