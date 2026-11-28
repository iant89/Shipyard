package dev.shipyard.metrics;

import com.sun.management.OperatingSystemMXBean;
import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.common.Time;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.runtime.ContainerStats;
import dev.shipyard.storage.StorageLayout;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Samples host and per-application resource usage every few seconds and keeps
 * a short in-memory history (about 15 minutes) for sparklines and charts.
 * Long-term historical metrics are a future feature.
 */
@Service
public class MetricsService {
    private static final int HISTORY = 180;

    private final ContainerRuntime runtime;
    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final StorageLayout layout;
    private final EventBus events;
    private final Map<String, Deque<MetricSample>> history = new ConcurrentHashMap<>();
    private final Map<String, long[]> lastNet = new ConcurrentHashMap<>();
    private final Deque<MetricSample> hostHistory = new ArrayDeque<>();
    private volatile long[] lastHostNet;
    private volatile long lastHostNetAt;
    private volatile boolean runtimeAvailable;

    public MetricsService(ContainerRuntime runtime, ApplicationRepository applications, DeploymentRepository deployments,
                          StorageLayout layout, EventBus events) {
        this.runtime = runtime;
        this.applications = applications;
        this.deployments = deployments;
        this.layout = layout;
        this.events = events;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 3000)
    void sample() {
        String now = Time.now();
        MetricSample host = sampleHost(now);
        synchronized (hostHistory) {
            hostHistory.addLast(host);
            while (hostHistory.size() > HISTORY) hostHistory.removeFirst();
        }
        runtimeAvailable = runtime.available();
        Map<String, Object> perApp = new HashMap<>();
        if (runtimeAvailable) {
            Map<String, ContainerStats> stats = runtime.statsAll();
            for (Application app : applications.findAll()) {
                if (app.currentDeploymentId() == null) continue;
                Deployment d = deployments.findById(app.currentDeploymentId()).orElse(null);
                if (d == null || d.containerName() == null || d.isStatic()) continue;
                ContainerStats s = stats.get(d.containerName());
                if (s == null) continue;
                long[] prev = lastNet.put(app.id(), new long[] {s.networkRxBytes(), s.networkTxBytes(), System.currentTimeMillis()});
                double rx = 0, tx = 0;
                if (prev != null) {
                    double secs = Math.max(1, (System.currentTimeMillis() - prev[2]) / 1000.0);
                    rx = Math.max(0, (s.networkRxBytes() - prev[0]) / secs);
                    tx = Math.max(0, (s.networkTxBytes() - prev[1]) / secs);
                }
                MetricSample m = new MetricSample(now, s.cpuPercent(), s.memoryBytes(), s.memoryLimitBytes(), rx, tx,
                        s.blockWriteBytes(), 0);
                Deque<MetricSample> h = history.computeIfAbsent(app.id(), k -> new ArrayDeque<>());
                synchronized (h) {
                    h.addLast(m);
                    while (h.size() > HISTORY) h.removeFirst();
                }
                perApp.put(app.id(), m);
            }
        }
        events.publish(EventTypes.METRICS_UPDATED, null, null, Map.of("host", host, "applications", perApp));
    }

    public boolean runtimeAvailable() {
        return runtimeAvailable;
    }

    public MetricSample latest(String applicationId) {
        Deque<MetricSample> h = history.get(applicationId);
        if (h == null) return null;
        synchronized (h) {
            return h.peekLast();
        }
    }

    public List<MetricSample> history(String applicationId) {
        Deque<MetricSample> h = history.get(applicationId);
        if (h == null) return List.of();
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    public MetricSample latestHost() {
        synchronized (hostHistory) {
            return hostHistory.isEmpty() ? sampleHost(Time.now()) : hostHistory.peekLast();
        }
    }

    public List<MetricSample> hostHistory() {
        synchronized (hostHistory) {
            return List.copyOf(hostHistory);
        }
    }

    public void forget(String applicationId) {
        history.remove(applicationId);
        lastNet.remove(applicationId);
    }

    private MetricSample sampleHost(String now) {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpu = Math.max(0, os.getCpuLoad()) * 100;
        HostMemory memory = readHostMemory(os.getTotalMemorySize(), os.getFreeMemorySize());
        long total = memory.totalBytes();
        long used = total - memory.availableBytes();
        long diskTotal = 0, diskUsed = 0;
        try {
            FileStore store = Files.getFileStore(layout.root());
            diskTotal = store.getTotalSpace();
            diskUsed = diskTotal - store.getUsableSpace();
        } catch (IOException ignored) {
            // unavailable
        }
        double rx = 0, tx = 0;
        long[] net = readHostNetwork();
        long t = System.currentTimeMillis();
        if (net != null && lastHostNet != null) {
            double secs = Math.max(1, (t - lastHostNetAt) / 1000.0);
            rx = Math.max(0, (net[0] - lastHostNet[0]) / secs);
            tx = Math.max(0, (net[1] - lastHostNet[1]) / secs);
        }
        lastHostNet = net;
        lastHostNetAt = t;
        return new MetricSample(now, cpu, used, total, rx, tx, diskUsed, diskTotal);
    }

    private record HostMemory(long totalBytes, long availableBytes) {}

    /**
     * Use Linux's MemAvailable instead of MemFree so reclaimable cache is not
     * counted as memory in use. Fall back to the JVM's OS bean on other hosts.
     */
    private static HostMemory readHostMemory(long fallbackTotalBytes, long fallbackAvailableBytes) {
        try {
            long totalBytes = 0;
            long availableBytes = -1;
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) totalBytes = parseMemInfoBytes(line);
                else if (line.startsWith("MemAvailable:")) availableBytes = parseMemInfoBytes(line);
            }
            if (totalBytes > 0 && availableBytes >= 0) {
                return new HostMemory(totalBytes, Math.min(totalBytes, availableBytes));
            }
        } catch (IOException | NumberFormatException ignored) {
            // Use the portable OS bean values if procfs is unavailable or malformed.
        }

        long totalBytes = Math.max(0, fallbackTotalBytes);
        long availableBytes = Math.max(0, Math.min(totalBytes, fallbackAvailableBytes));
        return new HostMemory(totalBytes, availableBytes);
    }

    private static long parseMemInfoBytes(String line) {
        String[] fields = line.strip().split("\\s+");
        if (fields.length < 2) throw new NumberFormatException("Invalid /proc/meminfo line");
        return Long.parseLong(fields[1]) * 1024;
    }

    /** Sums /proc/net/dev counters, excluding loopback and virtual interfaces. Linux only. */
    private static long[] readHostNetwork() {
        Path p = Path.of("/proc/net/dev");
        if (!Files.isReadable(p)) return null;
        try {
            long rx = 0, tx = 0;
            for (String line : Files.readAllLines(p)) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String iface = line.substring(0, colon).strip();
                if (iface.equals("lo") || iface.startsWith("veth") || iface.startsWith("docker") || iface.startsWith("br-")) continue;
                String[] f = line.substring(colon + 1).strip().split("\\s+");
                if (f.length < 9) continue;
                rx += Long.parseLong(f[0]);
                tx += Long.parseLong(f[8]);
            }
            return new long[] {rx, tx};
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }
}
