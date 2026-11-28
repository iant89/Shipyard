package dev.shipyard.applications;

import dev.shipyard.common.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persistent volumes of an application. Each container path (for example
 * {@code /data}) is backed by a named Docker volume, {@code shipyard-<slug>-<path>},
 * that outlives containers: every deployment, rollback and restart mounts the
 * same volume, and only deleting the application removes it.
 */
public final class Volumes {
    public static final int MAX = 10;
    /** Label on every volume Shipyard creates, valued with the application id. */
    public static final String LABEL_APPLICATION = "dev.shipyard.application";

    private Volumes() {}

    /** Validates and normalises container paths: absolute, no "..", no duplicates. */
    public static List<String> normalize(List<String> paths) {
        if (paths == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (String raw : paths) {
            if (raw == null || raw.isBlank()) continue;
            String p = raw.strip();
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (!p.startsWith("/")) throw new BadRequestException("Volume path must be absolute (start with /): " + raw.strip());
            if (p.equals("/")) throw new BadRequestException("The container's root directory cannot be a volume");
            if (p.contains("..") || p.contains(":") || p.contains(",") || p.chars().anyMatch(Character::isWhitespace)) {
                throw new BadRequestException("Invalid volume path: " + raw.strip());
            }
            if (p.length() > 200) throw new BadRequestException("Volume path is too long: " + p);
            if (out.add(p) && !names.add(suffix(p))) {
                throw new BadRequestException("Volume paths " + p + " and another path map to the same volume name; rename one");
            }
        }
        if (out.size() > MAX) throw new BadRequestException("At most " + MAX + " volumes per application");
        return List.copyOf(out);
    }

    /** Docker volume name for an application's container path. */
    public static String volumeName(String slug, String containerPath) {
        return "shipyard-" + slug + "-" + suffix(containerPath);
    }

    /** {@code /var/lib/app} becomes {@code var-lib-app}. */
    static String suffix(String containerPath) {
        String s = containerPath.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-").replaceAll("-{2,}", "-");
        s = s.replaceAll("^[-.]+|[-.]+$", "");
        return s.isEmpty() ? "data" : s;
    }

    public static String serialize(List<String> paths) {
        return paths == null ? "" : String.join("\n", paths);
    }

    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : stored.split("\n")) if (!l.isBlank()) out.add(l.strip());
        return List.copyOf(out);
    }
}
