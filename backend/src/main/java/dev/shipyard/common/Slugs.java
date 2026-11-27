package dev.shipyard.common;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {
    private Slugs() {}

    /** Docker- and DNS-safe slug: lowercase letters, digits and dashes. */
    public static String slugify(String input) {
        String s = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.isEmpty() ? "app" : s;
    }
}
