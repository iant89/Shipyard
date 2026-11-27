package dev.shipyard.repositories;

/** Source repository configuration of an application. */
public record GitRepository(
        String id,
        String applicationId,
        String provider,
        String url,
        String branch,
        String accessTokenEnc,
        String createdAt,
        String updatedAt) {

    public boolean isPrivate() {
        return accessTokenEnc != null;
    }
}
