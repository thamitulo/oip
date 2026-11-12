package za.co.oip.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP rate limit on authentication endpoints to slow down credential stuffing. Counters live in PostgreSQL
 * ({@link JdbcRateLimiter}) so the limit applies across all API instances.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final int limitPerMinute;
    private final JdbcRateLimiter limiter;

    public AuthRateLimitFilter(int limitPerMinute, JdbcRateLimiter limiter) {
        this.limitPerMinute = limitPerMinute;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitPerMinute <= 0 || !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.tryAcquire("auth:" + request.getRemoteAddr(), limitPerMinute)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Too many requests\",\"status\":429,"
                    + "\"detail\":\"Too many authentication attempts. Try again in a minute.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
