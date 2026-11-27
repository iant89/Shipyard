package dev.shipyard.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDetectorTest {
    private final ProjectDetector detector = new ProjectDetector();

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void reactViteIsStatic() throws IOException {
        write("package.json", """
                {"scripts":{"build":"vite build"},"dependencies":{"react":"^18"},"devDependencies":{"vite":"^5"}}""");
        write("package-lock.json", "{}");
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.projectType()).isEqualTo(ProjectType.STATIC);
        assertThat(r.buildStrategy()).isEqualTo(BuildStrategy.NODE_STATIC);
        assertThat(r.framework()).isEqualTo("React + Vite");
        assertThat(r.packageManager()).isEqualTo("npm");
        assertThat(r.installCommand()).isEqualTo("npm ci");
        assertThat(r.buildCommand()).isEqualTo("npm run build");
        assertThat(r.outputDirectory()).isEqualTo("dist");
        assertThat(r.source()).isEqualTo("FRAMEWORK");
    }

    @Test
    void nextServerAndStaticExport() throws IOException {
        write("package.json", """
                {"scripts":{"build":"next build","start":"next start"},"dependencies":{"next":"14"}}""");
        write("pnpm-lock.yaml", "");
        DetectionResult server = detector.detect(dir, null);
        assertThat(server.projectType()).isEqualTo(ProjectType.SERVER);
        assertThat(server.port()).isEqualTo(3000);
        assertThat(server.packageManager()).isEqualTo("pnpm");

        write("next.config.mjs", "export default { output: 'export' }");
        DetectionResult exported = detector.detect(dir, null);
        assertThat(exported.projectType()).isEqualTo(ProjectType.STATIC);
        assertThat(exported.outputDirectory()).isEqualTo("out");
    }

    @Test
    void expressServer() throws IOException {
        write("package.json", """
                {"scripts":{"start":"node server.js"},"dependencies":{"express":"4"}}""");
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.projectType()).isEqualTo(ProjectType.SERVER);
        assertThat(r.framework()).isEqualTo("Express");
        assertThat(r.startCommand()).isEqualTo("npm start");
    }

    @Test
    void springBootMaven() throws IOException {
        write("pom.xml", "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent>"
                + "<dependency><artifactId>spring-boot-starter-actuator</artifactId></dependency></project>");
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.buildStrategy()).isEqualTo(BuildStrategy.MAVEN);
        assertThat(r.framework()).isEqualTo("Spring Boot");
        assertThat(r.port()).isEqualTo(8080);
        assertThat(r.healthCheckPath()).isEqualTo("/actuator/health");
    }

    @Test
    void goRustPythonDotnetPhpHtml() throws IOException {
        Path go = Files.createDirectories(dir.resolve("go"));
        Files.writeString(go.resolve("go.mod"), "module x");
        assertThat(detector.detect(go, null).buildStrategy()).isEqualTo(BuildStrategy.GO);

        Path rust = Files.createDirectories(dir.resolve("rust"));
        Files.writeString(rust.resolve("Cargo.toml"), "[package]\nname = \"hello\"\n");
        assertThat(detector.detect(rust, null).startCommand()).isEqualTo("/app/hello");

        Path py = Files.createDirectories(dir.resolve("py"));
        Files.writeString(py.resolve("requirements.txt"), "fastapi\nuvicorn\n");
        Files.writeString(py.resolve("main.py"), "");
        DetectionResult p = detector.detect(py, null);
        assertThat(p.framework()).isEqualTo("FastAPI");
        assertThat(p.startCommand()).contains("uvicorn main:app");

        Path net = Files.createDirectories(dir.resolve("net"));
        Files.writeString(net.resolve("Api.csproj"), "<Project/>");
        assertThat(detector.detect(net, null).startCommand()).isEqualTo("dotnet /app/Api.dll");

        Path php = Files.createDirectories(dir.resolve("php"));
        Files.writeString(php.resolve("index.php"), "<?php");
        assertThat(detector.detect(php, null).buildStrategy()).isEqualTo(BuildStrategy.PHP);

        Path html = Files.createDirectories(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "<h1>hi</h1>");
        DetectionResult h = detector.detect(html, null);
        assertThat(h.projectType()).isEqualTo(ProjectType.STATIC);
        assertThat(h.buildStrategy()).isEqualTo(BuildStrategy.STATIC_FILES);
    }

    @Test
    void dockerfileOverridesFrameworkAndReadsExpose() throws IOException {
        write("package.json", """
                {"scripts":{"build":"vite build"},"devDependencies":{"vite":"^5"}}""");
        write("Dockerfile", "FROM nginx\nEXPOSE 9000\n");
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.buildStrategy()).isEqualTo(BuildStrategy.DOCKERFILE);
        assertThat(r.projectType()).isEqualTo(ProjectType.SERVER);
        assertThat(r.port()).isEqualTo(9000);
        assertThat(r.source()).isEqualTo("DOCKERFILE");
    }

    @Test
    void platformYmlOverridesDetection() throws IOException {
        write("package.json", """
                {"scripts":{"build":"vite build"},"devDependencies":{"vite":"^5"}}""");
        write("platform.yml", """
                name: my-app
                build:
                  command: npm run build:prod
                  output: build
                healthcheck:
                  path: /health
                """);
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.buildCommand()).isEqualTo("npm run build:prod");
        assertThat(r.outputDirectory()).isEqualTo("build");
        assertThat(r.healthCheckPath()).isEqualTo("/health");
        assertThat(r.source()).isEqualTo("PLATFORM_YML");

        write("platform.yml", """
                runtime:
                  command: npm start
                  port: 3000
                """);
        DetectionResult s = detector.detect(dir, null);
        assertThat(s.projectType()).isEqualTo(ProjectType.SERVER);
        assertThat(s.port()).isEqualTo(3000);
    }

    @Test
    void nothingDetectedNeedsManualConfiguration() {
        DetectionResult r = detector.detect(dir, null);
        assertThat(r.buildStrategy()).isNull();
        assertThat(r.source()).isEqualTo("MANUAL");
    }
}
