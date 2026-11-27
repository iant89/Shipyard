package dev.shipyard.builds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shipyard.detection.BuildStrategy;
import dev.shipyard.detection.DetectionResult;
import dev.shipyard.detection.ProjectType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerfileGeneratorTest {
    @Test
    void staticBuildExportsOnlyOutput() {
        DetectionResult d = DetectionResult.builder().projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC)
                .installCommand("npm ci").buildCommand("npm run build").testCommand("npm test").outputDirectory("dist")
                .runtime("node:lts-alpine").build();
        String f = DockerfileGenerator.generate(d, List.of("VITE_API_URL"));
        assertThat(f).contains("FROM node:lts-alpine AS build", "ARG VITE_API_URL", "RUN npm ci", "RUN npm run build", "RUN npm test",
                "FROM scratch AS export", "COPY --from=build /src/dist/ /");
    }

    @Test
    void nodeServerUsesShellCmd() {
        DetectionResult d = DetectionResult.builder().projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.NODE_SERVER)
                .installCommand("npm ci").startCommand("node server.js \"quoted\"").port(3000).build();
        String f = DockerfileGenerator.generate(d, List.of());
        assertThat(f).contains("EXPOSE 3000", "ENV PORT=3000", "CMD [\"sh\",\"-c\",\"node server.js \\\"quoted\\\"\"]");
    }

    @Test
    void mavenIsMultiStage() {
        DetectionResult d = DetectionResult.builder().projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.MAVEN)
                .buildCommand("mvn -B -DskipTests package").port(8080).runtime("eclipse-temurin:21-jre").build();
        String f = DockerfileGenerator.generate(d, List.of());
        assertThat(f).contains("FROM maven:3-eclipse-temurin-21 AS build", "FROM eclipse-temurin:21-jre", "java -jar /app/app.jar");
    }

    @Test
    void serverWithoutStartCommandFails() {
        DetectionResult d = DetectionResult.builder().projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.PYTHON).build();
        assertThatThrownBy(() -> DockerfileGenerator.generate(d, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
