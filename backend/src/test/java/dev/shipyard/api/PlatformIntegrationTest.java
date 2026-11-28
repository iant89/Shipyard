package dev.shipyard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.webhooks.GitHubWebhookProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end test of the platform API with a real git repository and a
 * mocked container runtime: setup/login, static deployment, webhook
 * security and routing, redeploy, rollback and secret masking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationTest {
    static Path dataDir;
    static Path repo;
    /** A stand-in for Shipyard's own repository, and the install checkout two commits behind it. */
    static Path shipyardOrigin;
    static Path shipyardCheckout;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ContainerRuntime runtime;

    static MockHttpSession session;
    static String appId;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        dataDir = Files.createTempDirectory("shipyard-it");
        repo = Files.createTempDirectory("shipyard-repo");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(repo.resolve("index.html"), "<h1>v1</h1>");
        git("add", ".");
        git("commit", "-q", "-m", "first");
        shipyardOrigin = Files.createTempDirectory("shipyard-origin");
        run(shipyardOrigin, "git", "init", "-q", "-b", "main");
        for (String m : List.of("v1", "v2", "v3")) {
            Files.writeString(shipyardOrigin.resolve("VERSION"), m);
            run(shipyardOrigin, "git", "add", ".");
            run(shipyardOrigin, "git", "-c", "user.email=t@example.com", "-c", "user.name=Release", "commit", "-q", "-m", "Release " + m);
        }
        shipyardCheckout = Files.createTempDirectory("shipyard-checkout");
        run(shipyardCheckout, "git", "clone", "-q", "file://" + shipyardOrigin, ".");
        run(shipyardCheckout, "git", "reset", "-q", "--hard", "HEAD~2");
        r.add("shipyard.source-dir", () -> shipyardCheckout.toString());
        r.add("shipyard.data-dir", () -> dataDir.toString());
        r.add("shipyard.auto-update-interval-seconds", () -> "2");
        r.add("shipyard.http-port", () -> "8081");
        r.add("shipyard.allow-local-repositories", () -> "true");
        r.add("shipyard.public-url", () -> "https://platform.example.com");
    }

    @BeforeAll
    static void noop() { }

    static String git(String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        return run(null, cmd.toArray(String[]::new));
    }

    static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        Process p = pb.redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        if (p.exitValue() != 0) throw new IllegalStateException(out);
        return out.strip();
    }

    @Test
    @Order(1)
    void requiresSetupAndAuthentication() throws Exception {
        mvc.perform(get("/api/auth/status")).andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(true));
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"short\"}")).andExpect(status().isBadRequest());

        MvcResult r = mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"correct-horse-battery\"}")).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.username").value("admin"));
        // A second setup is refused.
        mvc.perform(post("/api/auth/setup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"evil\",\"password\":\"correct-horse-battery\"}")).andExpect(status().isConflict());
        // Wrong password
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong-password-123\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void createsAndDeploysStaticApplication() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", "My Site", "repository", "file://" + repo, "branch", "main", "autoDeploy", true,
                "domains", List.of("site.example.com"), "deployNow", true,
                "environment", List.of(Map.of("name", "API_KEY", "value", "super-secret-value", "secret", true),
                        Map.of("name", "PUBLIC_URL", "value", "https://site.example.com", "secret", false))));
        MvcResult r = mvc.perform(post("/api/applications").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode app = mapper.readTree(r.getResponse().getContentAsString());
        appId = app.path("summary").path("id").asText();
        assertThat(app.path("summary").path("slug").asText()).isEqualTo("my-site");

        JsonNode d = awaitDeployment(1, "RUNNING");
        assertThat(d.path("projectType").asText()).isEqualTo("STATIC");
        assertThat(d.path("commitMessage").asText()).isEqualTo("first");
        Path dist = dataDir.resolve("deployments").resolve(appId).resolve("001").resolve("dist").resolve("index.html");
        assertThat(Files.readString(dist)).contains("v1");
        assertThat(Files.readSymbolicLink(dataDir.resolve("deployments").resolve(appId).resolve("current")).toString()).isEqualTo("001");
        String caddyfile = Files.readString(dataDir.resolve("caddy").resolve("Caddyfile"));
        assertThat(caddyfile).contains("site.example.com {").contains("/deployments/" + appId + "/001/dist");

        mvc.perform(get("/api/applications/" + appId).session(session)).andExpect(jsonPath("$.summary.status").value("RUNNING"));
        // Secrets are masked in API responses
        String env = mvc.perform(get("/api/applications/" + appId + "/environment").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(env).doesNotContain("super-secret-value").contains("https://site.example.com");
    }

    @Test
    @Order(3)
    void webhookSecurityAndRouting() throws Exception {
        JsonNode hook = json(mvc.perform(get("/api/applications/" + appId + "/webhook").session(session)).andReturn());
        String url = hook.path("endpointUrl").asText();
        assertThat(url).startsWith("https://platform.example.com/api/webhooks/github/");
        String path = url.substring("https://platform.example.com".length());
        String secret = json(mvc.perform(post("/api/applications/" + appId + "/webhook/secret/reveal").session(session).with(csrf())).andReturn())
                .path("secret").asText();

        Files.writeString(repo.resolve("index.html"), "<h1>v2</h1>");
        git("commit", "-q", "-am", "second");
        String sha = git("rev-parse", "HEAD");
        String fullName = RepositoryManager.fullName("file://" + repo);

        byte[] push = push("main", sha, fullName);
        // Invalid signature -> 401, no CSRF/session needed for webhooks
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(push)
                        .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", "d-0")
                        .header("X-Hub-Signature-256", GitHubWebhookProvider.sign("wrong", push)))
                .andExpect(status().isUnauthorized());
        // Unknown endpoint -> 404
        mvc.perform(post("/api/webhooks/github/doesnotexist").contentType(MediaType.APPLICATION_JSON).content(push))
                .andExpect(status().isNotFound());
        // Wrong branch -> recorded and ignored
        byte[] dev = push("development", sha, fullName);
        mvc.perform(signed(path, dev, secret, "d-dev")).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("IGNORED"));
        // Valid push -> 202 + queued
        mvc.perform(signed(path, push, secret, "d-1")).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        // GitHub retry with the same delivery id -> no new deployment
        mvc.perform(signed(path, push, secret, "d-1")).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        // Same commit again with a new delivery id -> ignored as already deployed/queued
        mvc.perform(signed(path, push, secret, "d-2")).andExpect(status().isAccepted()).andExpect(jsonPath("$.outcome").value("IGNORED"));

        JsonNode d2 = awaitDeployment(2, "RUNNING");
        assertThat(d2.path("source").asText()).isEqualTo("WEBHOOK");
        assertThat(d2.path("commitSha").asText()).isEqualTo(sha);
        assertThat(awaitDeployment(1, "STOPPED").path("status").asText()).isEqualTo("STOPPED");

        JsonNode events = json(mvc.perform(get("/api/applications/" + appId + "/webhook/events").session(session)).andReturn());
        assertThat(events.toString()).contains("DEPLOYED").contains("IGNORED").contains("Invalid signature");

        // Test webhook validates without deploying
        mvc.perform(post("/api/applications/" + appId + "/webhook/test").session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IGNORED"));
    }

    @Test
    @Order(4)
    void automaticDeploymentCanBeDisabled() throws Exception {
        mvc.perform(put("/api/applications/" + appId + "/webhook").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"automaticDeployment\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        String secret = json(mvc.perform(post("/api/applications/" + appId + "/webhook/secret/reveal").session(session).with(csrf())).andReturn())
                .path("secret").asText();
        String path = json(mvc.perform(get("/api/applications/" + appId + "/webhook").session(session)).andReturn())
                .path("endpointUrl").asText().substring("https://platform.example.com".length());
        Files.writeString(repo.resolve("index.html"), "<h1>v3</h1>");
        git("commit", "-q", "-am", "third");
        byte[] p = push("main", git("rev-parse", "HEAD"), RepositoryManager.fullName("file://" + repo));
        mvc.perform(signed(path, p, secret, "d-3")).andExpect(jsonPath("$.outcome").value("IGNORED"))
                .andExpect(jsonPath("$.message").value("Automatic deployment is disabled"));
    }

    @Test
    @Order(5)
    void rollsBackToPreviousDeployment() throws Exception {
        JsonNode list = json(mvc.perform(get("/api/applications/" + appId + "/deployments").session(session)).andReturn());
        String first = null;
        for (JsonNode d : list) if (d.path("number").asInt() == 1) first = d.path("id").asText();
        mvc.perform(post("/api/applications/" + appId + "/rollback").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deploymentId\":\"" + first + "\"}")).andExpect(status().isAccepted());
        awaitDeployment(1, "RUNNING");
        awaitDeployment(2, "ROLLED_BACK");
        assertThat(Files.readSymbolicLink(dataDir.resolve("deployments").resolve(appId).resolve("current")).toString()).isEqualTo("001");
        mvc.perform(get("/api/applications/" + appId).session(session)).andExpect(jsonPath("$.summary.currentDeployment.number").value(1));
    }

    @Test
    @Order(6)
    void failedBuildKeepsCurrentDeploymentOnline() throws Exception {
        // A server app without a start command cannot be built; the running deployment must stay current.
        Files.delete(repo.resolve("index.html"));
        Files.writeString(repo.resolve("README.md"), "nothing to build");
        git("add", "-A");
        git("commit", "-q", "-m", "break it");
        mvc.perform(post("/api/applications/" + appId + "/deploy").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isAccepted());
        JsonNode failed = awaitDeployment(3, "FAILED");
        assertThat(failed.path("error").asText()).contains("Could not detect");
        mvc.perform(get("/api/applications/" + appId).session(session))
                .andExpect(jsonPath("$.summary.status").value("RUNNING"))
                .andExpect(jsonPath("$.summary.currentDeployment.number").value(1));
        JsonNode logs = json(mvc.perform(get("/api/applications/" + appId + "/deployments/3/logs").session(session)).andReturn());
        assertThat(logs.path("deployment").toString()).contains("remains online");
    }

    @Test
    @Order(7)
    void reportsNewerCommitsThanTheRunningOne() throws Exception {
        // #1 ("first") is running; "second", "third" and "break it" have been pushed since.
        JsonNode u = json(mvc.perform(get("/api/applications/" + appId + "/updates").session(session)).andExpect(status().isOk()).andReturn());
        assertThat(u.path("status").asText()).isEqualTo("BEHIND");
        assertThat(u.path("branch").asText()).isEqualTo("main");
        assertThat(u.path("behind").asInt()).isEqualTo(3);
        assertThat(u.path("latestSha").asText()).isEqualTo(git("rev-parse", "HEAD"));
        List<String> messages = new java.util.ArrayList<>();
        u.path("commits").forEach(c -> messages.add(c.path("message").asText()));
        assertThat(messages).containsExactly("break it", "third", "second");
        assertThat(u.path("commits").get(0).path("author").asText()).isEqualTo("Test");

        // Cached until refreshed.
        Files.writeString(repo.resolve("README.md"), "still nothing to build");
        git("commit", "-q", "-am", "fourth");
        mvc.perform(get("/api/applications/" + appId + "/updates").session(session)).andExpect(jsonPath("$.behind").value(3));
        mvc.perform(get("/api/applications/" + appId + "/updates?refresh=true").session(session))
                .andExpect(jsonPath("$.behind").value(4)).andExpect(jsonPath("$.commits[0].message").value("fourth"));

        // Running the branch head means up to date.
        String head = git("rev-parse", "HEAD");
        git("reset", "-q", "--hard", u.path("currentSha").asText());
        mvc.perform(get("/api/applications/" + appId + "/updates?refresh=true").session(session))
                .andExpect(jsonPath("$.status").value("UP_TO_DATE")).andExpect(jsonPath("$.behind").value(0));
        git("reset", "-q", "--hard", head);
    }

    @Test
    @Order(8)
    void reportsShipyardUpdatesButRefusesWithoutAComposeInstall() throws Exception {
        String installed = run(shipyardCheckout, "git", "rev-parse", "HEAD");
        String latest = run(shipyardOrigin, "git", "rev-parse", "HEAD");
        JsonNode v = json(mvc.perform(get("/api/platform/update").session(session)).andExpect(status().isOk()).andReturn());
        assertThat(v.path("commit").asText()).isEqualTo(installed);
        assertThat(v.path("branch").asText()).isEqualTo("main");
        assertThat(v.path("check").path("status").asText()).isEqualTo("BEHIND");
        assertThat(v.path("check").path("behind").asInt()).isEqualTo(2);
        assertThat(v.path("check").path("latestSha").asText()).isEqualTo(latest);
        assertThat(v.path("check").path("commits").get(0).path("message").asText()).isEqualTo("Release v3");
        // Not running in a compose container here: updating is reported as unavailable, with a reason.
        assertThat(v.path("canUpdate").asBoolean()).isFalse();
        assertThat(v.path("reason").asText()).isNotBlank();

        mvc.perform(post("/api/platform/update").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commit\":\"" + latest + "\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/platform/update").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"commit\":\"nope\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/update/status").session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/platform/update")).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(9)
    void reportsTheOutcomeWrittenByTheUpdateScript() throws Exception {
        Path state = Files.createDirectories(dataDir.resolve("platform-update"));
        Files.writeString(state.resolve("status.json"), "{\"state\":\"ROLLED_BACK\",\"fromSha\":\"a\",\"toSha\":\"b\","
                + "\"message\":\"b did not become healthy; rolled back to a.\",\"startedAt\":\"2026-01-01T00:00:00Z\",\"finishedAt\":\"2026-01-01T00:05:00Z\"}");
        Files.writeString(state.resolve("update.log"), "line 1\nline 2\n");
        mvc.perform(get("/api/platform/update/status").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.message").value("b did not become healthy; rolled back to a."))
                .andExpect(jsonPath("$.log[1]").value("line 2"));
        mvc.perform(get("/api/platform/update").session(session)).andExpect(jsonPath("$.update.state").value("ROLLED_BACK"));
        // Without a session (the update restart signs everyone out): the outcome, but not the log.
        mvc.perform(get("/api/platform/update/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ROLLED_BACK")).andExpect(jsonPath("$.log").isEmpty());
    }

    @Test
    @Order(10)
    void autoUpdateDeploysNewCommitsOnce() throws Exception {
        // HEAD ("fourth", unbuildable like "break it") was never deployed; #3 failed on "break it".
        String head = git("rev-parse", "HEAD");
        mvc.perform(put("/api/applications/" + appId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"autoUpdate\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.summary.autoUpdate").value(true));

        JsonNode d = awaitDeployment(4, "FAILED");
        assertThat(d.path("source").asText()).isEqualTo("SCHEDULED");
        assertThat(d.path("commitSha").asText()).isEqualTo(head);
        assertThat(d.path("commitMessage").asText()).isEqualTo("fourth");

        // The failed commit is not retried on later polls (interval is 2 s in this test).
        Thread.sleep(6000);
        assertThat(deploymentNumbers()).doesNotContain(5);

        // A new push is picked up.
        Files.writeString(repo.resolve("index.html"), "<h1>v5</h1>");
        git("add", "-A");
        git("commit", "-q", "-m", "fifth");
        JsonNode fifth = awaitDeployment(5, "RUNNING");
        assertThat(fifth.path("source").asText()).isEqualTo("SCHEDULED");
        assertThat(fifth.path("commitMessage").asText()).isEqualTo("fifth");
        mvc.perform(get("/api/applications/" + appId).session(session)).andExpect(jsonPath("$.summary.currentDeployment.number").value(5));

        // Off again: new commits are left alone.
        mvc.perform(put("/api/applications/" + appId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"autoUpdate\":false}")).andExpect(jsonPath("$.summary.autoUpdate").value(false));
        Files.writeString(repo.resolve("index.html"), "<h1>v6</h1>");
        git("commit", "-q", "-am", "sixth");
        Thread.sleep(6000);
        assertThat(deploymentNumbers()).doesNotContain(6);
    }

    @Test
    @Order(11)
    void localHostnamesGetTheHttpPortInTheirUrl() throws Exception {
        mvc.perform(post("/api/applications/" + appId + "/domains").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"hostname\":\"devstick.local\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.tlsEnabled").value(false));
        JsonNode urls = json(mvc.perform(get("/api/applications/" + appId).session(session)).andReturn()).path("summary").path("urls");
        List<String> list = new java.util.ArrayList<>();
        urls.forEach(u -> list.add(u.asText()));
        assertThat(list).contains("http://devstick.local:8081", "http://site.example.com");
        mvc.perform(get("/api/platform/info").session(session)).andExpect(jsonPath("$.httpPort").value(8081));
    }

    private List<Integer> deploymentNumbers() throws Exception {
        List<Integer> numbers = new java.util.ArrayList<>();
        json(mvc.perform(get("/api/applications/" + appId + "/deployments").session(session)).andReturn())
                .forEach(x -> numbers.add(x.path("number").asInt()));
        return numbers;
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] push(String branch, String sha, String fullName) {
        return ("{\"ref\":\"refs/heads/" + branch + "\",\"before\":\"0\",\"after\":\"" + sha + "\",\"repository\":{\"full_name\":\""
                + fullName + "\"},\"head_commit\":{\"message\":\"msg\",\"author\":{\"name\":\"t\"}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static org.springframework.test.web.servlet.RequestBuilder signed(String path, byte[] body, String secret, String delivery) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", GitHubWebhookProvider.sign(secret, body));
    }

    private JsonNode awaitDeployment(int number, String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            JsonNode list = json(mvc.perform(get("/api/applications/" + appId + "/deployments").session(session)).andReturn());
            for (JsonNode d : list) {
                if (d.path("number").asInt() == number) {
                    last = d;
                    if (status.equals(d.path("status").asText())) return d;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Deployment #" + number + " did not reach " + status + "; last: " + last);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    static {
        // Mockito default for Optional-returning methods is Optional.empty(), which is what we want.
        Optional.empty();
    }
}
