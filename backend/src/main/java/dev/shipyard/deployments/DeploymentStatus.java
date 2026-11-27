package dev.shipyard.deployments;

import java.util.EnumSet;
import java.util.Set;

/** Deployment state machine (spec section 31). */
public enum DeploymentStatus {
    CREATED, QUEUED, CLONING, DETECTING, BUILDING, TESTING, DEPLOYING, STARTING, HEALTH_CHECK,
    RUNNING, STOPPING, STOPPED, FAILED, ROLLED_BACK, CANCELLED;

    public static final Set<DeploymentStatus> IN_PROGRESS =
            EnumSet.of(CLONING, DETECTING, BUILDING, TESTING, DEPLOYING, STARTING, HEALTH_CHECK);

    public static final Set<DeploymentStatus> PENDING = EnumSet.of(CREATED, QUEUED);

    public boolean isTerminal() {
        return this == FAILED || this == CANCELLED || this == STOPPED || this == ROLLED_BACK;
    }
}
