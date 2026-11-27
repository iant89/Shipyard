package dev.shipyard.detection;

/** How the build engine turns source into an artifact. */
public enum BuildStrategy {
    DOCKERFILE,
    STATIC_FILES,
    NODE_STATIC,
    NODE_SERVER,
    BUN_SERVER,
    PYTHON,
    GO,
    RUST,
    MAVEN,
    GRADLE,
    DOTNET,
    PHP
}
