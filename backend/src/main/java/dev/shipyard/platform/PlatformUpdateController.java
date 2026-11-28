package dev.shipyard.platform;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shipyard's own version and updates (Settings → Shipyard updates). */
@RestController
@RequestMapping("/api/platform/update")
public class PlatformUpdateController {
    public record StartRequest(String commit) {}

    private final PlatformUpdater updater;

    public PlatformUpdateController(PlatformUpdater updater) {
        this.updater = updater;
    }

    /** Running commit, newer commits on its branch (fetched, cached ~10 min) and the last update. */
    @GetMapping
    PlatformUpdater.PlatformVersion version(@RequestParam(defaultValue = "false") boolean refresh) {
        return updater.version(refresh);
    }

    /**
     * Progress of the running (or last) update, polled by the UI while Shipyard
     * restarts. Readable without a session, because the restart signs everyone
     * out; the log is only included for a signed-in administrator.
     */
    @GetMapping("/status")
    ResponseEntity<PlatformUpdater.UpdateStatus> status(Authentication auth) {
        var s = updater.status();
        if (s == null) return ResponseEntity.noContent().build();
        boolean admin = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMINISTRATOR"));
        if (!admin) s = new PlatformUpdater.UpdateStatus(s.state(), s.fromSha(), s.toSha(), s.message(), s.startedAt(), s.finishedAt(), List.of());
        return ResponseEntity.ok(s);
    }

    @PostMapping
    ResponseEntity<PlatformUpdater.UpdateStatus> start(@RequestBody StartRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updater.start(req == null ? null : req.commit()));
    }
}
