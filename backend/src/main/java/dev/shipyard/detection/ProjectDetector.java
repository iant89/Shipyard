package dev.shipyard.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.applications.ServiceConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects how to build and run a project. Layers are applied from lowest to
 * highest priority, so a higher layer overrides a lower one:
 *
 * <ol>
 *   <li>framework detection (package.json, pom.xml, go.mod, ...)</li>
 *   <li>platform.yml</li>
 *   <li>Dockerfile (switches the build to {@code docker build})</li>
 *   <li>explicit configuration stored on the service</li>
 * </ol>
 *
 * If nothing matches, the result has no strategy and needs manual configuration.
 */
@Component
public class ProjectDetector {
    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+(\\d+)");
    private static final Pattern VOLUME = Pattern.compile("(?im)^\\s*VOLUME\\s+(.+)$");
    private final ObjectMapper mapper = new ObjectMapper();

    public DetectionResult detect(Path sourceRoot, ServiceConfig explicit) {
        Path root = explicit != null && explicit.rootDirectory() != null && !explicit.rootDirectory().isBlank()
                ? sourceRoot.resolve(explicit.rootDirectory()).normalize() : sourceRoot;
        if (!root.startsWith(sourceRoot)) throw new IllegalArgumentException("Root directory escapes the repository");
        try {
            DetectionResult.Builder b = detectFramework(root);
            applyPlatformYml(root, b);
            applyDockerfile(root, b, explicit);
            if (explicit != null) applyExplicit(explicit, b);
            if (b.buildStrategy == null) {
                b.source("MANUAL").note("Could not detect the project type. Add a Dockerfile or platform.yml, or configure the build manually.");
            }
            if (b.projectType == ProjectType.SERVER && b.port == null) b.port(8080);
            if (b.healthCheckPath == null) b.healthCheckPath("/");
            if (b.healthCheckType == null) b.healthCheckType(b.projectType == ProjectType.STATIC ? "HTTP" : "HTTP");
            return b.build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ framework

    DetectionResult.Builder detectFramework(Path root) throws IOException {
        DetectionResult.Builder b = DetectionResult.builder();
        if (Files.isRegularFile(root.resolve("package.json"))) {
            detectNode(root, b);
        } else if (Files.isRegularFile(root.resolve("pom.xml"))) {
            boolean wrapper = Files.isRegularFile(root.resolve("mvnw"));
            String pom = Files.readString(root.resolve("pom.xml"));
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.MAVEN)
                    .framework(pom.contains("spring-boot") ? "Spring Boot" : "Maven")
                    .packageManager("maven")
                    .buildCommand((wrapper ? "./mvnw" : "mvn") + " -B -DskipTests package")
                    .startCommand("java -jar /app/app.jar").runtime("eclipse-temurin:21-jre").port(8080);
            if (pom.contains("spring-boot-starter-actuator")) b.healthCheckPath("/actuator/health");
        } else if (exists(root, "build.gradle") || exists(root, "build.gradle.kts")) {
            boolean wrapper = Files.isRegularFile(root.resolve("gradlew"));
            String gradle = read(root, "build.gradle").orElseGet(() -> read(root, "build.gradle.kts").orElse(""));
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.GRADLE)
                    .framework(gradle.contains("org.springframework.boot") ? "Spring Boot" : "Gradle")
                    .packageManager("gradle")
                    .buildCommand((wrapper ? "./gradlew" : "gradle") + " --no-daemon build -x test")
                    .startCommand("java -jar /app/app.jar").runtime("eclipse-temurin:21-jre").port(8080);
            if (gradle.contains("spring-boot-starter-actuator")) b.healthCheckPath("/actuator/health");
        } else if (exists(root, "go.mod")) {
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.GO).framework("Go").packageManager("go")
                    .buildCommand("CGO_ENABLED=0 go build -o /out/server .").startCommand("/app/server")
                    .runtime("alpine:3").port(8080);
        } else if (exists(root, "Cargo.toml")) {
            String name = read(root, "Cargo.toml").flatMap(ProjectDetector::cargoName).orElse("app");
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.RUST).framework("Rust").packageManager("cargo")
                    .buildCommand("cargo build --release").startCommand("/app/" + name)
                    .runtime("debian:bookworm-slim").port(8080);
        } else if (exists(root, "requirements.txt") || exists(root, "pyproject.toml")) {
            detectPython(root, b);
        } else if (findCsproj(root).isPresent()) {
            String project = findCsproj(root).get();
            String dll = project.replaceFirst("\\.csproj$", "") + ".dll";
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.DOTNET).framework(".NET").packageManager("dotnet")
                    .buildCommand("dotnet publish -c Release -o /out").startCommand("dotnet /app/" + dll)
                    .runtime("mcr.microsoft.com/dotnet/aspnet:8.0").port(8080);
        } else if (exists(root, "composer.json") || exists(root, "index.php")) {
            b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.PHP).framework("PHP")
                    .packageManager(exists(root, "composer.json") ? "composer" : null)
                    .installCommand(exists(root, "composer.json") ? "composer install --no-dev --optimize-autoloader" : null)
                    .runtime("php:8.3-apache").port(80);
        } else if (exists(root, "index.html")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.STATIC_FILES).framework("HTML").outputDirectory(".");
        }
        if (b.buildStrategy != null) b.source("FRAMEWORK");
        return b;
    }

    private void detectNode(Path root, DetectionResult.Builder b) throws IOException {
        JsonNode pkg = mapper.readTree(root.resolve("package.json").toFile());
        JsonNode scripts = pkg.path("scripts");
        String pm = exists(root, "pnpm-lock.yaml") ? "pnpm"
                : exists(root, "yarn.lock") ? "yarn"
                : exists(root, "bun.lockb") || exists(root, "bun.lock") ? "bun"
                : "npm";
        b.packageManager(pm).installCommand(installCommand(pm, root));
        String run = pm.equals("npm") ? "npm run " : pm + " run ";
        boolean hasBuild = scripts.hasNonNull("build");
        boolean hasStart = scripts.hasNonNull("start");
        if (scripts.hasNonNull("test") && !scripts.path("test").asText().contains("no test specified")) {
            b.note("A test script exists; set a test command to run it during builds.");
        }

        if (dep(pkg, "next")) {
            boolean export = Stream.of("next.config.js", "next.config.mjs", "next.config.ts", "next.config.cjs")
                    .map(f -> read(root, f).orElse(""))
                    .anyMatch(s -> s.matches("(?s).*output\\s*:\\s*['\"]export['\"].*"));
            b.framework("Next.js").buildCommand(run + "build");
            if (export) {
                b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("Next.js (static export)").outputDirectory("out");
            } else {
                b.projectType(ProjectType.SERVER).buildStrategy(nodeServer(pm)).startCommand(run + "start").port(3000);
            }
        } else if (dep(pkg, "nuxt")) {
            b.projectType(ProjectType.SERVER).buildStrategy(nodeServer(pm)).framework("Nuxt").buildCommand(run + "build")
                    .startCommand("node .output/server/index.mjs").port(3000);
        } else if (dep(pkg, "@sveltejs/kit")) {
            if (dep(pkg, "@sveltejs/adapter-static")) {
                b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("SvelteKit (static)")
                        .buildCommand(run + "build").outputDirectory("build");
            } else {
                b.projectType(ProjectType.SERVER).buildStrategy(nodeServer(pm)).framework("SvelteKit")
                        .buildCommand(run + "build").startCommand("node build").port(3000);
            }
        } else if (dep(pkg, "astro")) {
            if (dep(pkg, "@astrojs/node")) {
                b.projectType(ProjectType.SERVER).buildStrategy(nodeServer(pm)).framework("Astro (SSR)")
                        .buildCommand(run + "build").startCommand("node ./dist/server/entry.mjs").port(4321);
            } else {
                b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("Astro")
                        .buildCommand(run + "build").outputDirectory("dist");
            }
        } else if (dep(pkg, "@angular/core") || exists(root, "angular.json")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("Angular")
                    .buildCommand(run + "build").outputDirectory(angularOutput(root));
        } else if (dep(pkg, "gatsby")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("Gatsby")
                    .buildCommand(run + "build").outputDirectory("public");
        } else if (dep(pkg, "react-scripts")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("React (Create React App)")
                    .buildCommand(run + "build").outputDirectory("build");
        } else if (dep(pkg, "vite") || Stream.of("vite.config.js", "vite.config.ts", "vite.config.mjs", "vite.config.mts").anyMatch(f -> exists(root, f))) {
            String ui = dep(pkg, "react") ? "React" : dep(pkg, "vue") ? "Vue" : dep(pkg, "svelte") ? "Svelte"
                    : dep(pkg, "solid-js") ? "Solid" : dep(pkg, "preact") ? "Preact" : null;
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC)
                    .framework(ui == null ? "Vite" : ui + " + Vite")
                    .buildCommand(run + "build").outputDirectory("dist");
        } else if (dep(pkg, "@vue/cli-service")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("Vue CLI")
                    .buildCommand(run + "build").outputDirectory("dist");
        } else if (hasStart || pkg.hasNonNull("main")) {
            String fw = dep(pkg, "express") ? "Express" : dep(pkg, "fastify") ? "Fastify" : dep(pkg, "@nestjs/core") ? "NestJS"
                    : dep(pkg, "hono") ? "Hono" : dep(pkg, "koa") ? "Koa" : pm.equals("bun") ? "Bun" : "Node.js";
            b.projectType(ProjectType.SERVER).buildStrategy(nodeServer(pm)).framework(fw)
                    .buildCommand(hasBuild ? run + "build" : null)
                    .startCommand(hasStart ? (pm.equals("npm") ? "npm start" : pm + " run start") : "node " + pkg.path("main").asText())
                    .port(3000);
        } else if (hasBuild) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.NODE_STATIC).framework("JavaScript")
                    .buildCommand(run + "build").outputDirectory("dist")
                    .note("Assumed a static build into dist/; adjust the output directory if needed.");
        } else if (exists(root, "index.html")) {
            b.projectType(ProjectType.STATIC).buildStrategy(BuildStrategy.STATIC_FILES).framework("HTML").outputDirectory(".");
        }
        b.runtime(pm.equals("bun") ? "oven/bun:1" : "node:lts-alpine");
    }

    private static BuildStrategy nodeServer(String pm) {
        return pm.equals("bun") ? BuildStrategy.BUN_SERVER : BuildStrategy.NODE_SERVER;
    }

    private static String installCommand(String pm, Path root) {
        return switch (pm) {
            case "pnpm" -> "corepack enable && pnpm install --frozen-lockfile";
            case "yarn" -> "corepack enable && yarn install";
            case "bun" -> "bun install";
            default -> exists(root, "package-lock.json") ? "npm ci" : "npm install";
        };
    }

    private String angularOutput(Path root) {
        try {
            if (!exists(root, "angular.json")) return "dist";
            JsonNode a = mapper.readTree(root.resolve("angular.json").toFile());
            var projects = a.path("projects");
            if (!projects.fields().hasNext()) return "dist";
            var first = projects.fields().next();
            JsonNode opts = first.getValue().path("architect").path("build").path("options");
            JsonNode out = opts.path("outputPath");
            String builder = first.getValue().path("architect").path("build").path("builder").asText("");
            String base = out.isTextual() ? out.asText() : out.path("base").asText("dist/" + first.getKey());
            // The application builder (Angular 17+) writes the browser bundle into a "browser" subfolder.
            return builder.contains(":application") ? base + "/browser" : base;
        } catch (IOException e) {
            return "dist";
        }
    }

    private void detectPython(Path root, DetectionResult.Builder b) {
        String reqs = (read(root, "requirements.txt").orElse("") + "\n" + read(root, "pyproject.toml").orElse("")).toLowerCase(Locale.ROOT);
        b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.PYTHON).packageManager("pip")
                .installCommand(exists(root, "requirements.txt") ? "pip install --no-cache-dir -r requirements.txt" : "pip install --no-cache-dir .")
                .runtime("python:3.12-slim").port(8000);
        String module = exists(root, "main.py") ? "main" : exists(root, "app.py") ? "app" : "app";
        if (exists(root, "manage.py")) {
            b.framework("Django").startCommand(reqs.contains("gunicorn")
                    ? "gunicorn --bind 0.0.0.0:8000 $(basename $(dirname $(ls */wsgi.py | head -n1))).wsgi"
                    : "python manage.py runserver 0.0.0.0:8000");
        } else if (reqs.contains("fastapi")) {
            b.framework("FastAPI").startCommand("uvicorn " + module + ":app --host 0.0.0.0 --port 8000");
        } else if (reqs.contains("flask")) {
            b.framework("Flask").startCommand(reqs.contains("gunicorn")
                    ? "gunicorn --bind 0.0.0.0:8000 " + module + ":app"
                    : "flask --app " + module + " run --host 0.0.0.0 --port 8000");
        } else {
            b.framework("Python").startCommand("python " + module + ".py");
        }
        b.note("Verify the Python start command; it was inferred from the project layout.");
    }

    // ------------------------------------------------------------------ platform.yml / Dockerfile / explicit

    private void applyPlatformYml(Path root, DetectionResult.Builder b) throws IOException {
        PlatformConfig c = PlatformConfig.load(root);
        if (c == null) return;
        b.source("PLATFORM_YML").note("Configuration loaded from platform.yml");
        if (c.type() != null) {
            ProjectType t = ProjectType.valueOf(c.type().toUpperCase(Locale.ROOT));
            b.projectType(t);
            if (t == ProjectType.STATIC && (b.buildStrategy == null || !isStaticStrategy(b.buildStrategy))) {
                b.buildStrategy(c.buildCommand() == null ? BuildStrategy.STATIC_FILES : BuildStrategy.NODE_STATIC);
            }
        }
        if (c.install() != null) b.installCommand(c.install());
        if (c.buildCommand() != null) b.buildCommand(c.buildCommand());
        if (c.test() != null) b.testCommand(c.test());
        if (c.output() != null) {
            b.outputDirectory(c.output());
            if (c.type() == null && c.startCommand() == null) {
                b.projectType(ProjectType.STATIC);
                if (b.buildStrategy == null || !isStaticStrategy(b.buildStrategy)) b.buildStrategy(BuildStrategy.NODE_STATIC);
            }
        }
        if (c.startCommand() != null) {
            b.startCommand(c.startCommand());
            if (c.type() == null) b.projectType(ProjectType.SERVER);
            if (b.buildStrategy == null || isStaticStrategy(b.buildStrategy)) b.buildStrategy(BuildStrategy.NODE_SERVER);
        }
        if (c.port() != null) b.port(c.port());
        if (c.image() != null) b.runtime(c.image());
        if (c.healthPath() != null) b.healthCheckPath(c.healthPath());
        if (c.healthType() != null) b.healthCheckType(c.healthType().toUpperCase(Locale.ROOT));
        if (c.dockerfile() != null) {
            b.buildStrategy(BuildStrategy.DOCKERFILE).projectType(ProjectType.SERVER).note("Dockerfile: " + c.dockerfile());
        }
    }

    private void applyDockerfile(Path root, DetectionResult.Builder b, ServiceConfig explicit) throws IOException {
        if (!exists(root, "Dockerfile")) return;
        if (explicit != null && explicit.buildStrategy() != null && !"DOCKERFILE".equals(explicit.buildStrategy())) return;
        if (b.buildStrategy != null && isStaticStrategy(b.buildStrategy) && "PLATFORM_YML".equals(b.source)) {
            b.note("Dockerfile ignored because platform.yml declares a static application.");
            return;
        }
        String dockerfile = Files.readString(root.resolve("Dockerfile"));
        String fw = b.framework;
        b.projectType(ProjectType.SERVER).buildStrategy(BuildStrategy.DOCKERFILE).source("DOCKERFILE")
                .framework(fw == null ? "Dockerfile" : "Dockerfile (" + fw + ")")
                .installCommand(null).buildCommand("docker build").runtime("Dockerfile").outputDirectory(null);
        Matcher m = EXPOSE.matcher(dockerfile);
        if (m.find()) b.port(Integer.parseInt(m.group(1)));
        List<String> declared = dockerfileVolumes(dockerfile);
        b.volumes(declared);
        if (!declared.isEmpty()) {
            b.note("The Dockerfile declares VOLUME " + String.join(", ", declared)
                    + ". Add " + (declared.size() == 1 ? "it" : "them") + " under Volumes to keep the data across deployments.");
        }
    }

    /** Paths from VOLUME instructions (shell or JSON form). */
    static List<String> dockerfileVolumes(String dockerfile) {
        List<String> out = new java.util.ArrayList<>();
        Matcher m = VOLUME.matcher(dockerfile);
        while (m.find()) {
            for (String part : m.group(1).replaceAll("[\\[\\]\",]", " ").strip().split("\\s+")) {
                if (part.startsWith("/") && !out.contains(part)) out.add(part);
            }
        }
        return out;
    }

    private static void applyExplicit(ServiceConfig s, DetectionResult.Builder b) {
        boolean any = false;
        if (s.buildStrategy() != null) { b.buildStrategy(BuildStrategy.valueOf(s.buildStrategy())); any = true; }
        if (s.projectType() != null) { b.projectType(ProjectType.valueOf(s.projectType())); any = true; }
        if (s.framework() != null) b.framework(s.framework());
        if (s.installCommand() != null) { b.installCommand(blankToNull(s.installCommand())); any = true; }
        if (s.buildCommand() != null) { b.buildCommand(blankToNull(s.buildCommand())); any = true; }
        if (s.testCommand() != null) { b.testCommand(blankToNull(s.testCommand())); any = true; }
        if (s.startCommand() != null) { b.startCommand(blankToNull(s.startCommand())); any = true; }
        if (s.outputDirectory() != null) { b.outputDirectory(blankToNull(s.outputDirectory())); any = true; }
        if (s.packageManager() != null) b.packageManager(s.packageManager());
        if (s.runtimeImage() != null) { b.runtime(s.runtimeImage()); any = true; }
        if (s.internalPort() != null) { b.port(s.internalPort()); any = true; }
        if (s.healthCheckPath() != null) b.healthCheckPath(s.healthCheckPath());
        if (s.healthCheckType() != null) b.healthCheckType(s.healthCheckType());
        if (any) b.source("EXPLICIT");
    }

    static boolean isStaticStrategy(BuildStrategy s) {
        return s == BuildStrategy.NODE_STATIC || s == BuildStrategy.STATIC_FILES;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean dep(JsonNode pkg, String name) {
        return pkg.path("dependencies").has(name) || pkg.path("devDependencies").has(name);
    }

    private static boolean exists(Path root, String file) {
        return Files.exists(root.resolve(file));
    }

    private static Optional<String> read(Path root, String file) {
        try {
            Path p = root.resolve(file);
            return Files.isRegularFile(p) ? Optional.of(Files.readString(p)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> cargoName(String toml) {
        Matcher m = Pattern.compile("(?s)\\[package\\].*?\\bname\\s*=\\s*\"([^\"]+)\"").matcher(toml);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static Optional<String> findCsproj(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".csproj")).sorted().findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
