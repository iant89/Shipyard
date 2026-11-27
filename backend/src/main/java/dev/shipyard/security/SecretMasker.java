package dev.shipyard.security;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Replaces known secret values in log lines with a mask so secrets never
 * reach build logs, deployment logs, application logs or the UI.
 */
public final class SecretMasker {
    public static final String MASK = "********";
    private static final SecretMasker NONE = new SecretMasker(List.of());

    private final List<String> secrets;

    public SecretMasker(Collection<String> secrets) {
        // Longest first so overlapping secrets are fully masked; ignore trivially short values
        // which would otherwise mask ordinary words.
        this.secrets = secrets.stream()
                .filter(s -> s != null && s.length() >= 4)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public static SecretMasker none() {
        return NONE;
    }

    public String mask(String line) {
        if (line == null || secrets.isEmpty()) return line;
        String out = line;
        for (String s : secrets) {
            if (out.contains(s)) out = out.replace(s, MASK);
        }
        return out;
    }
}
