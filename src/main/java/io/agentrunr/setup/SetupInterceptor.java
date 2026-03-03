package io.agentrunr.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Redirects to /setup when no AI providers are configured.
 * Allows API endpoints, static resources, and the setup page itself to pass through.
 */
@Component
public class SetupInterceptor implements HandlerInterceptor {

    private final CredentialStore credentialStore;

    public SetupInterceptor(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // Always allow: setup page, API endpoints, static resources
        if (path.startsWith("/setup") || path.startsWith("/api/") ||
            path.startsWith("/css/") || path.startsWith("/js/") ||
            path.equals("/favicon.ico") || path.endsWith(".png") ||
            path.endsWith(".jpg") || path.endsWith(".svg") || path.endsWith(".ico")) {
            return true;
        }

        // Redirect to setup if not configured
        if (!credentialStore.isConfigured()) {
            response.sendRedirect("/setup");
            return false;
        }

        return true;
    }
}
