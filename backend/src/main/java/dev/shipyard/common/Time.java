package dev.shipyard.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Time {
    private Time() {}

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
