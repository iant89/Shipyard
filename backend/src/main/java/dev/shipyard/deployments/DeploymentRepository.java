package dev.shipyard.deployments;

import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeploymentRepository {
    static final RowMapper<Deployment> MAPPER = (rs, i) -> new Deployment(
            rs.getString("id"), rs.getString("application_id"), rs.getString("service_id"), rs.getInt("number"),
            rs.getString("commit_sha"), rs.getString("commit_message"), rs.getString("commit_author"), rs.getString("branch"),
            DeploymentSource.valueOf(rs.getString("source")), DeploymentStatus.valueOf(rs.getString("status")),
            rs.getString("build_status"), rs.getString("runtime_status"), rs.getString("project_type"), rs.getString("framework"),
            rs.getString("image_tag"), rs.getString("artifact_path"), rs.getString("container_name"),
            (Integer) rs.getObject("internal_port"), rs.getString("rollback_of"), rs.getString("triggered_by"),
            rs.getString("created_at"), rs.getString("started_at"), rs.getString("finished_at"), rs.getString("error"));

    private final JdbcTemplate jdbc;

    public DeploymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Deployment> findById(String id) {
        return jdbc.query("SELECT * FROM deployments WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Deployment get(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Deployment not found: " + id));
    }

    public Optional<Deployment> findByNumber(String applicationId, int number) {
        return jdbc.query("SELECT * FROM deployments WHERE application_id = ? AND number = ?", MAPPER, applicationId, number).stream().findFirst();
    }

    public List<Deployment> findByApplication(String applicationId, int limit) {
        return jdbc.query("SELECT * FROM deployments WHERE application_id = ? ORDER BY number DESC LIMIT ?", MAPPER, applicationId, limit);
    }

    public List<Deployment> recent(int limit) {
        return jdbc.query("SELECT * FROM deployments ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    public List<Deployment> findByStatuses(Collection<DeploymentStatus> statuses) {
        String in = statuses.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        return jdbc.query("SELECT * FROM deployments WHERE status IN (" + in + ") ORDER BY created_at", MAPPER);
    }

    public boolean existsActiveForCommit(String applicationId, String sha) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM deployments WHERE application_id = ? AND commit_sha = ?
                  AND status IN ('CREATED','QUEUED','CLONING','DETECTING','BUILDING','TESTING','DEPLOYING','STARTING','HEALTH_CHECK','RUNNING')""",
                Long.class, applicationId, sha);
        return n != null && n > 0;
    }

    public int nextNumber(String applicationId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(number), 0) + 1 FROM deployments WHERE application_id = ?", Integer.class, applicationId);
        return n == null ? 1 : n;
    }

    public void insert(Deployment d) {
        jdbc.update("""
                INSERT INTO deployments(id, application_id, service_id, number, commit_sha, commit_message, commit_author, branch, source,
                  status, build_status, runtime_status, project_type, framework, image_tag, artifact_path, container_name, internal_port,
                  rollback_of, triggered_by, created_at, started_at, finished_at, error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                d.id(), d.applicationId(), d.serviceId(), d.number(), d.commitSha(), d.commitMessage(), d.commitAuthor(), d.branch(),
                d.source().name(), d.status().name(), d.buildStatus(), d.runtimeStatus(), d.projectType(), d.framework(), d.imageTag(),
                d.artifactPath(), d.containerName(), d.internalPort(), d.rollbackOf(), d.triggeredBy(), d.createdAt(), d.startedAt(),
                d.finishedAt(), d.error());
    }

    public void updateStatus(String id, DeploymentStatus status) {
        jdbc.update("UPDATE deployments SET status = ? WHERE id = ?", status.name(), id);
    }

    public void markStarted(String id) {
        jdbc.update("UPDATE deployments SET started_at = COALESCE(started_at, ?) WHERE id = ?", Time.now(), id);
    }

    public void finish(String id, DeploymentStatus status, String error) {
        jdbc.update("UPDATE deployments SET status = ?, finished_at = ?, error = ? WHERE id = ?", status.name(), Time.now(), error, id);
    }

    public void setBuildStatus(String id, String status) {
        jdbc.update("UPDATE deployments SET build_status = ? WHERE id = ?", status, id);
    }

    public void setRuntimeStatus(String id, String status) {
        jdbc.update("UPDATE deployments SET runtime_status = ? WHERE id = ?", status, id);
    }

    public void setCommit(String id, String sha, String message, String author) {
        jdbc.update("UPDATE deployments SET commit_sha = ?, commit_message = ?, commit_author = ? WHERE id = ?", sha, message, author, id);
    }

    public void setDetection(String id, String projectType, String framework, Integer port) {
        jdbc.update("UPDATE deployments SET project_type = ?, framework = ?, internal_port = ? WHERE id = ?", projectType, framework, port, id);
    }

    public void setArtifact(String id, String imageTag, String artifactPath) {
        jdbc.update("UPDATE deployments SET image_tag = ?, artifact_path = ? WHERE id = ?", imageTag, artifactPath, id);
    }

    public void setContainer(String id, String containerName) {
        jdbc.update("UPDATE deployments SET container_name = ? WHERE id = ?", containerName, id);
    }
}
