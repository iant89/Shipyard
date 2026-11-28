package dev.shipyard.ide;

import dev.shipyard.api.ApplicationViews;
import dev.shipyard.api.ApplicationViews.DeploymentSummary;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentService;
import dev.shipyard.deployments.DeploymentSource;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

/** Application-scoped IDE workspace API. Spring Security restricts /api/** to administrators. */
@RestController
@RequestMapping("/api/applications/{id}/workspace")
public class ProjectWorkspaceController {
    public record PathRequest(String path) {}
    public record WriteRequest(String path, String content) {}
    public record RenameRequest(String path, String name) {}
    public record TransferRequest(String source, String destination) {}
    public record UploadRequest(String directory, String name) {}
    public record BranchRequest(String branch) {}
    public record CommitRequest(String message) {}
    private record WorkspaceCommit(String branch, String sha) {}

    private final ProjectWorkspaceService workspace;
    private final ApplicationRepository applications;
    private final GitRepositoryStore repositories;
    private final RepositoryManager git;
    private final DeploymentService deployments;
    private final WorkspaceBuildService builds;

    public ProjectWorkspaceController(ProjectWorkspaceService workspace, ApplicationRepository applications, GitRepositoryStore repositories,
                                      RepositoryManager git, DeploymentService deployments, WorkspaceBuildService builds) {
        this.workspace = workspace;
        this.applications = applications;
        this.repositories = repositories;
        this.git = git;
        this.deployments = deployments;
        this.builds = builds;
    }

    @GetMapping
    ProjectWorkspaceService.WorkspaceInfo info(@PathVariable String id) {
        return workspace.info(id);
    }

    @PostMapping("/fetch")
    ProjectWorkspaceService.WorkspaceInfo fetch(@PathVariable String id) {
        applications.get(id);
        var repo = repositories.forApplication(id);
        return git.withApplicationLock(id, () -> {
            git.fetchWorkspace(repo);
            return workspace.info(id);
        });
    }

    @GetMapping("/history")
    List<RepositoryManager.CommitInfo> history(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        applications.get(id);
        return git.workspaceHistory(repositories.forApplication(id), limit);
    }

    @GetMapping("/history/{sha}/files")
    RepositoryManager.WorkspaceCommitFiles commitFiles(@PathVariable String id, @PathVariable String sha) {
        applications.get(id);
        return git.workspaceCommitFiles(repositories.forApplication(id), sha);
    }

    @GetMapping("/history/{sha}/diff")
    RepositoryManager.WorkspaceCommitDiff commitDiff(@PathVariable String id, @PathVariable String sha, @RequestParam String path) {
        applications.get(id);
        return git.workspaceCommitDiff(repositories.forApplication(id), sha, path);
    }

    @GetMapping("/files")
    ProjectWorkspaceService.DirectoryListing files(@PathVariable String id, @RequestParam(defaultValue = "") String path) {
        return workspace.list(id, path);
    }

    @GetMapping("/all-files")
    ProjectWorkspaceService.FileIndex index(@PathVariable String id) {
        return workspace.index(id);
    }

    @GetMapping("/file")
    ProjectWorkspaceService.TextFile read(@PathVariable String id, @RequestParam String path) {
        return workspace.read(id, path);
    }

    @PostMapping("/file")
    ResponseEntity<Void> createFile(@PathVariable String id, @RequestBody PathRequest req) {
        workspace.createFile(id, requiredPath(req));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/file")
    ResponseEntity<Void> writeFile(@PathVariable String id, @RequestBody WriteRequest req) {
        if (req == null) throw new BadRequestException("File path and content are required");
        workspace.write(id, req.path(), req.content());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/directory")
    ResponseEntity<Void> createDirectory(@PathVariable String id, @RequestBody PathRequest req) {
        workspace.createDirectory(id, requiredPath(req));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/rename")
    ResponseEntity<Void> rename(@PathVariable String id, @RequestBody RenameRequest req) {
        if (req == null) throw new BadRequestException("Path and new name are required");
        workspace.rename(id, req.path(), req.name());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/copy")
    ResponseEntity<Void> copy(@PathVariable String id, @RequestBody TransferRequest req) {
        if (req == null) throw new BadRequestException("Source and destination paths are required");
        workspace.copy(id, req.source(), req.destination());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/move")
    ResponseEntity<Void> move(@PathVariable String id, @RequestBody TransferRequest req) {
        if (req == null) throw new BadRequestException("Source and destination paths are required");
        workspace.move(id, req.source(), req.destination());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/file")
    ResponseEntity<Void> delete(@PathVariable String id, @RequestParam String path) {
        workspace.delete(id, path);
        return ResponseEntity.noContent().build();
    }

    /** Raw binary upload is streamed and size-limited before it can consume unbounded memory. */
    @PostMapping(path = "/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Void> upload(@PathVariable String id, @RequestParam(defaultValue = "") String directory,
                                @RequestParam String name, HttpServletRequest request) {
        try {
            workspace.upload(id, directory, name, request.getInputStream(), request.getContentLengthLong());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/download")
    ResponseEntity<byte[]> download(@PathVariable String id, @RequestParam String path) {
        ProjectWorkspaceService.Download file = workspace.download(id, path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    @GetMapping("/search")
    ProjectWorkspaceService.SearchResponse search(@PathVariable String id, @RequestParam String q) {
        return workspace.search(id, q);
    }

    @PostMapping("/branch")
    ProjectWorkspaceService.WorkspaceInfo checkoutBranch(@PathVariable String id, @RequestBody BranchRequest req) {
        if (req == null || req.branch() == null) throw new BadRequestException("Branch is required");
        applications.get(id);
        var repo = repositories.forApplication(id);
        return git.withApplicationLock(id, () -> {
            git.checkoutWorkspaceBranch(repo, req.branch());
            return workspace.info(id);
        });
    }

    @PostMapping("/commit")
    ProjectWorkspaceService.WorkspaceInfo commit(@PathVariable String id, @RequestBody CommitRequest req) {
        if (req == null) throw new BadRequestException("Commit message is required");
        applications.get(id);
        var repo = repositories.forApplication(id);
        return git.withApplicationLock(id, () -> {
            git.commitWorkspace(repo, req.message());
            return workspace.info(id);
        });
    }

    @PostMapping("/push")
    ProjectWorkspaceService.WorkspaceInfo push(@PathVariable String id) {
        applications.get(id);
        var repo = repositories.forApplication(id);
        return git.withApplicationLock(id, () -> {
            var state = git.workspaceGitState(repo);
            git.pushWorkspace(repo, state.branch());
            return workspace.info(id);
        });
    }

    @PostMapping("/deploy")
    ResponseEntity<DeploymentSummary> deploy(@PathVariable String id, @RequestBody(required = false) CommitRequest req) {
        applications.get(id);
        var repo = repositories.forApplication(id);
        String message = req == null || req.message() == null || req.message().isBlank() ? "Deploy from IDE" : req.message().strip();
        WorkspaceCommit committed = git.withApplicationLock(id, () -> {
            String sha = git.commitWorkspace(repo, message);
            return new WorkspaceCommit(git.workspaceGitState(repo).branch(), sha);
        });
        Deployment d = deployments.request(new DeploymentService.DeploymentRequest(id, DeploymentSource.IDE, null, committed.sha(), committed.branch(),
                message, "Shipyard IDE", "user"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApplicationViews.deployment(d));
    }

    @PostMapping("/build")
    WorkspaceBuildService.BuildResult build(@PathVariable String id) {
        return builds.build(id);
    }

    private static String requiredPath(PathRequest req) {
        if (req == null || req.path() == null || req.path().isBlank()) throw new BadRequestException("Path is required");
        return req.path();
    }
}
