package dev.shipyard.detection;

import java.util.List;

/**
 * What the platform detected (or was told) about a project.
 *
 * @param source where the decisive configuration came from:
 *               EXPLICIT, DOCKERFILE, PLATFORM_YML, FRAMEWORK or MANUAL
 */
public record DetectionResult(
        ProjectType projectType,
        BuildStrategy buildStrategy,
        String framework,
        String packageManager,
        String installCommand,
        String buildCommand,
        String testCommand,
        String startCommand,
        String outputDirectory,
        String runtime,
        Integer port,
        String healthCheckPath,
        String healthCheckType,
        String source,
        List<String> notes,
        /** Paths the Dockerfile declares with VOLUME (suggested persistent volumes). */
        List<String> volumes) {

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(null);
    }

    public static final class Builder {
        ProjectType projectType;
        BuildStrategy buildStrategy;
        String framework;
        String packageManager;
        String installCommand;
        String buildCommand;
        String testCommand;
        String startCommand;
        String outputDirectory;
        String runtime;
        Integer port;
        String healthCheckPath;
        String healthCheckType;
        String source;
        java.util.ArrayList<String> notes = new java.util.ArrayList<>();
        List<String> volumes = List.of();

        Builder(DetectionResult r) {
            if (r == null) return;
            projectType = r.projectType; buildStrategy = r.buildStrategy; framework = r.framework;
            packageManager = r.packageManager; installCommand = r.installCommand; buildCommand = r.buildCommand;
            testCommand = r.testCommand; startCommand = r.startCommand; outputDirectory = r.outputDirectory;
            runtime = r.runtime; port = r.port; healthCheckPath = r.healthCheckPath; healthCheckType = r.healthCheckType;
            source = r.source; notes.addAll(r.notes); volumes = r.volumes == null ? List.of() : r.volumes;
        }

        public Builder projectType(ProjectType v) { projectType = v; return this; }
        public Builder buildStrategy(BuildStrategy v) { buildStrategy = v; return this; }
        public Builder framework(String v) { framework = v; return this; }
        public Builder packageManager(String v) { packageManager = v; return this; }
        public Builder installCommand(String v) { installCommand = v; return this; }
        public Builder buildCommand(String v) { buildCommand = v; return this; }
        public Builder testCommand(String v) { testCommand = v; return this; }
        public Builder startCommand(String v) { startCommand = v; return this; }
        public Builder outputDirectory(String v) { outputDirectory = v; return this; }
        public Builder runtime(String v) { runtime = v; return this; }
        public Builder port(Integer v) { port = v; return this; }
        public Builder healthCheckPath(String v) { healthCheckPath = v; return this; }
        public Builder healthCheckType(String v) { healthCheckType = v; return this; }
        public Builder source(String v) { source = v; return this; }
        public Builder note(String v) { notes.add(v); return this; }
        public Builder volumes(List<String> v) { volumes = v == null ? List.of() : List.copyOf(v); return this; }

        public DetectionResult build() {
            return new DetectionResult(projectType, buildStrategy, framework, packageManager, installCommand, buildCommand,
                    testCommand, startCommand, outputDirectory, runtime, port, healthCheckPath, healthCheckType, source, List.copyOf(notes), volumes);
        }
    }
}
