package dev.shipyard.domains;

import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.Ids;
import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainService {
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    private final DomainRepository domains;
    private final CaddyManager caddy;
    private final AuditService audit;

    public DomainService(DomainRepository domains, CaddyManager caddy, AuditService audit) {
        this.domains = domains;
        this.caddy = caddy;
        this.audit = audit;
    }

    public List<Domain> list(String applicationId) {
        return domains.findByApplication(applicationId);
    }

    @Transactional
    public Domain add(String applicationId, String hostname, boolean tls, boolean primary) {
        String h = normalize(hostname);
        if (domains.hostnameExists(h)) throw new ConflictException("Domain already in use: " + h);
        boolean first = domains.findByApplication(applicationId).isEmpty();
        if (primary || first) domains.clearPrimary(applicationId);
        Domain d = new Domain(Ids.uuid(), applicationId, h, tls, primary || first, Time.now());
        domains.insert(d);
        audit.record(AuditActions.DOMAIN_CHANGED, "APPLICATION", applicationId, Map.of("added", h));
        caddy.reloadAsync();
        return d;
    }

    @Transactional
    public Domain update(String applicationId, String domainId, Boolean tls, Boolean primary) {
        Domain d = get(applicationId, domainId);
        if (Boolean.TRUE.equals(primary)) domains.clearPrimary(applicationId);
        Domain updated = new Domain(d.id(), d.applicationId(), d.hostname(), tls != null ? tls : d.tlsEnabled(),
                primary != null ? primary : d.primary(), d.createdAt());
        domains.update(updated);
        audit.record(AuditActions.DOMAIN_CHANGED, "APPLICATION", applicationId, Map.of("updated", d.hostname()));
        caddy.reloadAsync();
        return updated;
    }

    @Transactional
    public void remove(String applicationId, String domainId) {
        Domain d = get(applicationId, domainId);
        domains.delete(d.id());
        audit.record(AuditActions.DOMAIN_CHANGED, "APPLICATION", applicationId, Map.of("removed", d.hostname()));
        caddy.reloadAsync();
    }

    private Domain get(String applicationId, String domainId) {
        return domains.findById(domainId).filter(x -> x.applicationId().equals(applicationId))
                .orElseThrow(() -> new NotFoundException("Domain not found"));
    }

    public static String normalize(String hostname) {
        if (hostname == null) throw new BadRequestException("Hostname is required");
        String h = hostname.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (!HOSTNAME.matcher(h).matches()) throw new BadRequestException("Invalid hostname: " + hostname);
        return h;
    }
}
