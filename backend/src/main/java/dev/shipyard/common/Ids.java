package dev.shipyard.common;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Random, unpredictable, non-sequential token encoded as hex. */
    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
