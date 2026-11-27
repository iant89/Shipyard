package dev.shipyard.detection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Optional {@code platform.yml} in the repository root:
 *
 * <pre>
 * name: my-app
 * type: server            # static | server (optional)
 * build:
 *   install: npm ci
 *   command: npm run build
 *   test: npm test
 *   output: dist
 *   dockerfile: Dockerfile
 * runtime:
 *   command: npm start
 *   port: 3000
 *   image: node:lts-alpine
 * healthcheck:
 *   type: http             # http | tcp | process | none
 *   path: /health
 * </pre>
 */
public record PlatformConfig(
        String name, String type,
        String install, String buildCommand, String test, String output, String dockerfile,
        String startCommand, Integer port, String image,
        String healthType, String healthPath) {

    public static final String[] FILE_NAMES = {"platform.yml", "platform.yaml", ".platform.yml"};

    public static PlatformConfig load(Path root) throws IOException {
        for (String name : FILE_NAMES) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    return parse(in);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static PlatformConfig parse(InputStream in) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(doc instanceof Map<?, ?> m)) return null;
        Map<String, Object> root = (Map<String, Object>) m;
        Map<String, Object> build = map(root.get("build"));
        Map<String, Object> runtime = map(root.get("runtime"));
        Map<String, Object> health = map(root.get("healthcheck"));
        return new PlatformConfig(
                str(root.get("name")), str(root.get("type")),
                str(build.get("install")), str(build.get("command")), str(build.get("test")), str(build.get("output")),
                str(build.get("dockerfile")),
                str(runtime.get("command")), integer(runtime.get("port")), str(runtime.get("image")),
                str(health.get("type")), str(health.get("path")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && s.matches("\\d+")) return Integer.parseInt(s);
        return null;
    }
}
