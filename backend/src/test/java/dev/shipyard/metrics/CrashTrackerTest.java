package dev.shipyard.metrics;

import static dev.shipyard.metrics.CrashTracker.Verdict.CRASHED;
import static dev.shipyard.metrics.CrashTracker.Verdict.HEALTHY;
import static dev.shipyard.metrics.CrashTracker.Verdict.UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrashTrackerTest {
    private static final long MIN = 60_000;

    @Test
    void crashLoopIsDetectedAndOnlyRecoversAfterStablePeriod() {
        CrashTracker t = new CrashTracker(5, 10 * MIN, 2 * MIN);
        assertThat(t.observe("d", true, true, false, 0, false, 0)).isEqualTo(UNCHANGED);
        assertThat(t.observe("d", true, true, false, 3, false, 10_000)).isEqualTo(UNCHANGED);
        assertThat(t.observe("d", true, true, false, 6, false, 20_000)).isEqualTo(CRASHED);
        // Running again between crashes: still crashed until stable for 2 minutes.
        assertThat(t.observe("d", true, true, false, 6, true, 30_000)).isEqualTo(UNCHANGED);
        assertThat(t.observe("d", true, true, false, 7, true, 90_000)).isEqualTo(UNCHANGED);
        assertThat(t.observe("d", true, true, false, 7, true, 90_000 + 2 * MIN)).isEqualTo(HEALTHY);
        assertThat(t.observe("d", true, true, false, 7, false, 100_000 + 2 * MIN)).isEqualTo(UNCHANGED);
    }

    @Test
    void occasionalRestartsSpreadOverTimeAreNotACrash() {
        CrashTracker t = new CrashTracker(5, 10 * MIN, 2 * MIN);
        t.observe("d", true, true, false, 10, false, 0); // baseline: lifetime count is ignored
        for (int i = 1; i <= 6; i++) {
            assertThat(t.observe("d", true, true, false, 10 + i, false, i * 30 * MIN)).isEqualTo(UNCHANGED);
        }
    }

    @Test
    void exitedOrMissingContainerIsCrashed() {
        CrashTracker t = new CrashTracker(5, 10 * MIN, 2 * MIN);
        assertThat(t.observe("a", true, false, true, 0, false, 0)).isEqualTo(CRASHED);
        assertThat(t.observe("b", false, false, false, 0, false, 0)).isEqualTo(CRASHED);
    }
}
