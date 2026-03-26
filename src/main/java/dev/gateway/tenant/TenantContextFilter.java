package dev.gateway.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that binds the current tenant's UUID into a Java 25
 * {@link ScopedValue} for the duration of the HTTP request.
 *
 * <h2>Execution order</h2>
 * This filter runs <em>after</em> {@code BearerTokenAuthenticationFilter} so
 * the
 * {@link org.springframework.security.core.context.SecurityContext} is already
 * populated with a validated {@link JwtAuthenticationToken}. The tenant UUID is
 * taken from {@link JwtAuthenticationToken#getDetails()}, which was set by
 * {@link JwtTenantConverter}.
 *
 * <h2>ScopedValue binding</h2>
 * {@link ScopedValue#where(ScopedValue, Object)} creates an immutable binding
 * that
 * is automatically cleared when the {@code run()} block exits — even if an
 * exception
 * is thrown. This is safer than {@code ThreadLocal.remove()} which can be
 * forgotten.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        // Skip tenant extraction for public resources and actuator endpoints
        if (path.startsWith("/actuator") || path.startsWith("/swagger-ui") || 
            path.startsWith("/api-docs") || path.equals("/") || 
            path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String tenantStr = jwtAuth.getToken().getClaimAsString("tenant_id");
            if (tenantStr != null && !tenantStr.isBlank()) {
                UUID tenantId = UUID.fromString(tenantStr.trim());
                log.debug("Binding tenant [{}] to ThreadLocal for request [{}]", tenantId, request.getRequestURI());

                try {
                    TenantContext.setTenantId(tenantId);
                    filterChain.doFilter(request, response);
                } finally {
                    TenantContext.clear();
                }
            } else {
                log.trace("Missing tenant_id claim; rejecting request for [{}]", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing tenant_id claim");
            }
        } else {
            log.trace("No JwtAuthenticationToken found; skipping tenant binding for [{}]", request.getRequestURI());
            filterChain.doFilter(request, response);
        }
    }
}
