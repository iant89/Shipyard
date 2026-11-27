package dev.shipyard.webhooks;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public webhook endpoint: {@code POST /api/webhooks/github/{webhook-id}}.
 * The body is bound as raw bytes so the signature is computed over exactly
 * what GitHub sent. Responds 202 once the event is recorded (and, for valid
 * pushes, queued) — never after a build.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    private static final int MAX_BODY = 25 * 1024 * 1024;

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping("/{provider}/{endpointId}")
    ResponseEntity<Map<String, Object>> receive(@PathVariable String provider, @PathVariable String endpointId,
                                                @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        byte[] raw = body == null ? new byte[0] : body;
        if (raw.length > MAX_BODY) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        WebhookService.Result r = service.receive(provider.toUpperCase(Locale.ROOT), endpointId, headers, raw);
        HttpStatus status = switch (r.outcome()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.ACCEPTED;
        };
        Map<String, Object> resp = new HashMap<>();
        resp.put("outcome", r.outcome().name());
        resp.put("message", r.message());
        if (r.eventId() != null) resp.put("eventId", r.eventId());
        if (r.deploymentId() != null) resp.put("deploymentId", r.deploymentId());
        return ResponseEntity.status(status).body(resp);
    }
}
