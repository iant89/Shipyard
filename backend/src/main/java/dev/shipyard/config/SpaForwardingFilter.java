package dev.shipyard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Serves the single-page frontend's index.html for client-side routes. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SpaForwardingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if ("GET".equals(req.getMethod()) && !path.startsWith("/api/") && !path.startsWith("/actuator")
                && !path.equals("/") && !path.substring(path.lastIndexOf('/') + 1).contains(".")) {
            req.getRequestDispatcher("/index.html").forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
