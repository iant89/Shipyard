package dev.shipyard.auth;

import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(@NotBlank String username, @NotBlank String password) {}
    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contexts;
    private final UserService users;
    private final AuditService audit;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contexts, UserService users, AuditService audit) {
        this.authManager = authManager;
        this.contexts = contexts;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/status")
    Map<String, Object> status(Principal principal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("setupRequired", users.setupRequired());
        m.put("authenticated", principal != null);
        m.put("username", principal != null ? principal.getName() : null);
        return m;
    }

    @PostMapping("/setup")
    ResponseEntity<?> setup(@RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        users.createInitialAdmin(body.username(), body.password());
        return login(body, req, res);
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody Credentials body, HttpServletRequest req, HttpServletResponse res) {
        Authentication auth;
        try {
            auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", 401, "detail", "Invalid username or password"));
        }
        // Prevent session fixation.
        HttpSession existing = req.getSession(false);
        if (existing != null) existing.invalidate();
        req.getSession(true);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, req, res);
        audit.record(AuditActions.USER_LOGGED_IN, "USER", auth.getName(), Map.of());
        return ResponseEntity.ok(Map.of("username", auth.getName()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    Map<String, Object> me(Principal principal) {
        if (principal == null) throw new BadCredentialsException("Not authenticated");
        return Map.of("username", principal.getName(), "role", "ADMINISTRATOR");
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@RequestBody PasswordChange body, Principal principal) {
        users.changePassword(principal.getName(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
