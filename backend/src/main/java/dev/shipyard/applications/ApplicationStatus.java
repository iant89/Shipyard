package dev.shipyard.applications;

public enum ApplicationStatus {
    CREATED, BUILDING, STARTING, RUNNING, STOPPED, CRASHED, FAILED, DEPLOYING, ROLLING_BACK;

    /** Whether the app URL should show its maintenance page instead of the active deployment. */
    public boolean isUpdating() {
        return this == BUILDING || this == STARTING || this == DEPLOYING || this == ROLLING_BACK;
    }
}
