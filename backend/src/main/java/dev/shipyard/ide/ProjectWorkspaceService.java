package dev.shipyard.ide;

import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.NotFoundException;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Secure file and Git workspace operations for the browser IDE. */
@Service
public class ProjectWorkspaceService {
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TRANSFER_BYTES = 25 * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 2_000;
    private static final int MAX_FILE_INDEX_ENTRIES = 10_000;
    private static final int MAX_SEARCH_FILES = 20_000;
    private static final long MAX_SEARCH_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SEARCH_RESULTS = 200;
    private static final long MAX_SEARCH_FILE_BYTES = 1024 * 1024;
    private static final List<String> SEARCH_EXCLUDED_DIRECTORIES = List.of(
            ".git", "node_modules", "target", "dist", "build", "out", "coverage", ".next", ".nuxt", ".output", ".svelte-kit",
            ".cache", ".venv", "venv", ".gradle", "__pycache__", ".pytest_cache");

    public record Entry(String name, String path, String kind, long size) {}
    public record DirectoryListing(String path, List<Entry> entries, boolean truncated) {}
    public record FileIndex(List<Entry> entries, boolean truncated) {}
    public record TextFile(String path, String content, long size) {}
    public record SearchMatch(String path, int lineNumber, String preview) {}
    public record SearchResponse(List<SearchMatch> matches, boolean truncated) {}
    public record WorkspaceInfo(String branch, List<String> branches, String headSha, List<String> changes) {}
    public record Download(String name, byte[] content) {}

    private final ApplicationRepository applications;
    private final GitRepositoryStore repositories;
    private final RepositoryManager git;

    public ProjectWorkspaceService(ApplicationRepository applications, GitRepositoryStore repositories, RepositoryManager git) {
        this.applications = applications;
        this.repositories = repositories;
        this.git = git;
    }

    public WorkspaceInfo info(String applicationId) {
        applications.get(applicationId);
        GitRepository repo = repositories.forApplication(applicationId);
        return git.withApplicationLock(applicationId, () -> {
            RepositoryManager.WorkspaceGitState state = git.workspaceGitState(repo);
            return new WorkspaceInfo(state.branch(), git.workspaceBranches(repo), state.headSha(), state.changes());
        });
    }

    public DirectoryListing list(String applicationId, String path) {
        return withWorkspace(applicationId, (root, repo) -> {
            Path directory = resolve(root, path, true, false);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("Directory not found");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new BadRequestException("Path is not a directory");
            List<Entry> entries = new ArrayList<>();
            boolean truncated = false;
            try (var stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    String name = child.getFileName().toString();
                    if (name.equalsIgnoreCase(".git")) continue;
                    if (entries.size() >= MAX_DIRECTORY_ENTRIES) {
                        truncated = true;
                        break;
                    }
                    String rel = root.relativize(child).toString().replace(child.getFileSystem().getSeparator(), "/");
                    if (Files.isSymbolicLink(child)) {
                        entries.add(new Entry(name, rel, "SYMLINK", 0));
                    } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        entries.add(new Entry(name, rel, "DIRECTORY", 0));
                    } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        entries.add(new Entry(name, rel, "FILE", Files.size(child)));
                    } else {
                        entries.add(new Entry(name, rel, "OTHER", 0));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            entries.sort(Comparator.comparing((Entry e) -> !e.kind().equals("DIRECTORY"))
                    .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            return new DirectoryListing(path == null ? "" : path, entries, truncated);
        });
    }

    /** A bounded project-wide file list used by the IDE's quick-open picker. */
    public FileIndex index(String applicationId) {
        return withWorkspace(applicationId, (root, repo) -> {
            List<Entry> entries = new ArrayList<>();
            boolean[] truncated = {false};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        if (!directory.equals(root) && SEARCH_EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(file) || !attrs.isRegularFile()
                                || file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                        if (entries.size() >= MAX_FILE_INDEX_ENTRIES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                        entries.add(new Entry(file.getFileName().toString(), relative, "FILE", attrs.size()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            entries.sort(Comparator.comparing(Entry::path, String.CASE_INSENSITIVE_ORDER));
            return new FileIndex(List.copyOf(entries), truncated[0]);
        });
    }

    public TextFile read(String applicationId, String path) {
        return withWorkspace(applicationId, (root, repo) -> {
            Path file = resolve(root, path, false, false);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File not found");
            try {
                long size = Files.size(file);
                if (size > MAX_TEXT_BYTES) throw new BadRequestException("File is too large to edit in the browser (max 2 MiB)");
                byte[] bytes = Files.readAllBytes(file);
                if (containsNul(bytes)) throw new BadRequestException("Binary files cannot be opened in the editor");
                String content;
                try {
                    content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException e) {
                    throw new BadRequestException("File is not valid UTF-8 and cannot be opened in the editor");
                }
                return new TextFile(path, content, size);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public void createFile(String applicationId, String path) {
        withWorkspace(applicationId, (root, repo) -> {
            Path file = resolve(root, path, false, false);
            requireParentDirectory(file);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
            try {
                Files.createFile(file);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConflictException("A file or directory already exists at that path");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    public void createDirectory(String applicationId, String path) {
        withWorkspace(applicationId, (root, repo) -> {
            Path directory = resolve(root, path, false, false);
            requireParentDirectory(directory);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
            try {
                Files.createDirectory(directory);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConflictException("A file or directory already exists at that path");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    public void write(String applicationId, String path, String content) {
        if (content == null) throw new BadRequestException("File content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new BadRequestException("File is too large to save (max 2 MiB)");
        if (containsNul(bytes)) throw new BadRequestException("Text files cannot contain NUL bytes");
        withWorkspace(applicationId, (root, repo) -> {
            Path file = resolve(root, path, false, false);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File not found");
            atomicWrite(file, bytes);
            return null;
        });
    }

    public void upload(String applicationId, String directory, String name, InputStream content, long contentLength) {
        if (content == null) throw new BadRequestException("Upload content is required");
        if (contentLength > MAX_TRANSFER_BYTES) throw new BadRequestException("Upload is too large (max 25 MiB)");
        validateName(name);
        withWorkspace(applicationId, (root, repo) -> {
            Path parent = resolve(root, directory, true, false);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new BadRequestException("Upload destination is not a directory");
            Path target = resolve(root, (directory == null || directory.isBlank() ? "" : directory + "/") + name, false, false);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
            Path tmp = null;
            try {
                tmp = Files.createTempFile(parent, ".shipyard-upload-", ".tmp");
                long total = 0;
                byte[] buffer = new byte[8192];
                try (InputStream in = content; OutputStream out = Files.newOutputStream(tmp)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_TRANSFER_BYTES) throw new BadRequestException("Upload is too large (max 25 MiB)");
                        out.write(buffer, 0, read);
                    }
                }
                Files.move(tmp, target);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConflictException("A file or directory already exists at that path");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            return null;
        });
    }

    public Download download(String applicationId, String path) {
        return withWorkspace(applicationId, (root, repo) -> {
            Path file = resolve(root, path, false, false);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File not found");
            try {
                long size = Files.size(file);
                if (size > MAX_TRANSFER_BYTES) throw new BadRequestException("File is too large to download through the IDE (max 25 MiB)");
                return new Download(file.getFileName().toString(), Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public void rename(String applicationId, String path, String newName) {
        validateName(newName);
        withWorkspace(applicationId, (root, repo) -> {
            Path source = resolve(root, path, false, true);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File or directory not found");
            Path destination = source.getParent().resolve(newName).normalize();
            if (!destination.startsWith(root.toRealPath())) throw new BadRequestException("Destination escapes the project");
            rejectGitPath(root, destination);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
            movePath(source, destination);
            return null;
        });
    }

    public void copy(String applicationId, String sourcePath, String destinationPath) {
        withWorkspace(applicationId, (root, repo) -> {
            Path source = resolve(root, sourcePath, false, false);
            Path destination = resolve(root, destinationPath, false, false);
            copyPath(source, destination);
            return null;
        });
    }

    public void move(String applicationId, String sourcePath, String destinationPath) {
        withWorkspace(applicationId, (root, repo) -> {
            Path source = resolve(root, sourcePath, false, true);
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File or directory not found");
            Path destination = resolve(root, destinationPath, false, false);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && destination.startsWith(source)) {
                throw new BadRequestException("A directory cannot be moved into itself");
            }
            requireParentDirectory(destination);
            movePath(source, destination);
            return null;
        });
    }

    public void delete(String applicationId, String path) {
        withWorkspace(applicationId, (root, repo) -> {
            Path target = resolve(root, path, false, true);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("File or directory not found");
            deleteTree(target);
            return null;
        });
    }

    public SearchResponse search(String applicationId, String query) {
        if (query == null || query.strip().length() < 2) return new SearchResponse(List.of(), false);
        String needle = query.strip().toLowerCase(Locale.ROOT);
        if (needle.length() > 200) throw new BadRequestException("Search query is too long");
        return withWorkspace(applicationId, (root, repo) -> {
            List<SearchMatch> matches = new ArrayList<>();
            int[] visited = {0};
            long[] scannedBytes = {0};
            boolean[] truncated = {false};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && SEARCH_EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(file) || !attrs.isRegularFile()
                                || file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                        if (++visited[0] > MAX_SEARCH_FILES || matches.size() >= MAX_SEARCH_RESULTS) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                        if (relative.startsWith(".git/")) return FileVisitResult.CONTINUE;
                        String filename = file.getFileName().toString();
                        if (filename.toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(new SearchMatch(relative, 0, "File name matches"));
                        }
                        if (matches.size() >= MAX_SEARCH_RESULTS) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        try {
                            long size = Files.size(file);
                            if (size > MAX_SEARCH_FILE_BYTES) return FileVisitResult.CONTINUE;
                            if (scannedBytes[0] + size > MAX_SEARCH_TOTAL_BYTES) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            scannedBytes[0] += size;
                            byte[] bytes = Files.readAllBytes(file);
                            if (containsNul(bytes)) return FileVisitResult.CONTINUE;
                            String text = new String(bytes, StandardCharsets.UTF_8);
                            String[] lines = text.split("\\R", -1);
                            for (int i = 0; i < lines.length; i++) {
                                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                                    matches.add(new SearchMatch(relative, i + 1, truncate(lines[i], 240)));
                                    if (matches.size() >= MAX_SEARCH_RESULTS) {
                                        truncated[0] = true;
                                        return FileVisitResult.TERMINATE;
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                            // A file can disappear while the search runs; continue with the rest.
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new SearchResponse(matches, truncated[0]);
        });
    }

    private <T> T withWorkspace(String applicationId, java.util.function.BiFunction<Path, GitRepository, T> action) {
        applications.get(applicationId);
        GitRepository repo = repositories.forApplication(applicationId);
        return git.withApplicationLock(applicationId, () -> action.apply(git.ensureWorkspace(repo), repo));
    }

    private static Path resolve(Path root, String path, boolean allowRoot, boolean allowLeafSymlink) {
        String value = path == null ? "" : path;
        if (value.isEmpty()) {
            if (allowRoot) return realRoot(root);
            throw new BadRequestException("A project-relative path is required");
        }
        if (value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0
                || value.chars().anyMatch(Character::isISOControl) || value.matches("^[A-Za-z]:.*")) {
            throw new BadRequestException("Use a relative project path");
        }
        String[] parts = value.split("/", -1);
        Path base = realRoot(root);
        Path resolved = base;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new BadRequestException("Invalid project path");
            if (part.equalsIgnoreCase(".git")) throw new BadRequestException("Git metadata is not accessible in the IDE");
            resolved = resolved.resolve(part).normalize();
            if (!resolved.startsWith(base)) throw new BadRequestException("Path escapes the project");
            if (Files.isSymbolicLink(resolved) && !(allowLeafSymlink && i == parts.length - 1)) {
                throw new BadRequestException("Symbolic links cannot be followed in the IDE");
            }
        }
        return resolved;
    }

    private static Path realRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rejectGitPath(Path root, Path destination) {
        Path relative = root.toRealPath().relativize(destination);
        for (Path part : relative) {
            if (part.toString().equalsIgnoreCase(".git")) throw new BadRequestException("Git metadata is not accessible in the IDE");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || name.indexOf('\0') >= 0 || name.chars().anyMatch(Character::isISOControl) || name.equalsIgnoreCase(".git")) {
            throw new BadRequestException("Invalid file or directory name");
        }
    }

    private static void requireParentDirectory(Path path) {
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new BadRequestException("Parent directory does not exist");
        }
    }

    private static void atomicWrite(Path file, byte[] bytes) {
        Path parent = file.getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(parent, ".shipyard-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    private static void movePath(Path source, Path destination) {
        requireParentDirectory(destination);
        try {
            Files.move(source, destination);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new ConflictException("A file or directory already exists at that path");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyPath(Path source, Path destination) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new NotFoundException("Source file or directory not found");
        if (Files.isSymbolicLink(source)) throw new BadRequestException("Symbolic links cannot be copied in the IDE");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new ConflictException("A file or directory already exists at that path");
        requireParentDirectory(destination);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            if (destination.startsWith(source)) throw new BadRequestException("A directory cannot be copied into itself");
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (!dir.equals(source) && dir.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.SKIP_SUBTREE;
                        Path target = destination.resolve(source.relativize(dir));
                        Files.createDirectory(target);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                        if (Files.isSymbolicLink(file)) throw new BadRequestException("A directory containing symbolic links cannot be copied");
                        Files.copy(file, destination.resolve(source.relativize(file)));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                deleteQuietly(destination);
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                deleteQuietly(destination);
                throw e;
            }
        } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.copy(source, destination);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            throw new BadRequestException("Only files and directories can be copied");
        }
    }

    private static void deleteTree(Path target) {
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return true;
        return false;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
