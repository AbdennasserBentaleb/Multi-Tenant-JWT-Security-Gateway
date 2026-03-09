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
 * Servlet filter that binds the current tenant's UUID into a Java 21
 * {@link ThreadLocal} for the duration of the HTTP request.
 *
 * <h2>Execution order</h2>
 * This filter runs <em>after</em> {@code BearerTokenAuthenticationFilter} so
 * the
 * {@link org.springframework.security.core.context.SecurityContext} is already
 * populated with a validated {@link JwtAuthenticationToken}. The tenant UUID is
 * taken from {@link JwtAuthenticationToken#getDetails()}, which was set by
 * {@link JwtTenantConverter}.
 *
 * <h2>ThreadLocal binding</h2>
 * The tenant context is set for the current request-handling thread and must be
 * manually cleared in a {@code finally} block when the request processing finishes
 * to prevent memory leaks in the servlet container's thread pool.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth
                && jwtAuth.getDetails() instanceof UUID tenantId) {

            log.debug("Binding tenant [{}] to ThreadLocal for request [{}]",
                    tenantId, request.getRequestURI());

            // ThreadLocal binding
            try {
                TenantContext.setTenantId(tenantId);
                filterChain.doFilter(request, response);
            } catch (ServletException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException("Unexpected error in TenantContextFilter", e);
            } finally {
                TenantContext.clear();
            }

        } else {
            // No JWT authentication found — let the filter chain handle it
            // (Spring Security will reject unauthenticated requests downstream)
            log.trace("No JwtAuthenticationToken found; skipping tenant binding for [{}]",
                    request.getRequestURI());
            filterChain.doFilter(request, response);
        }
    }
}
