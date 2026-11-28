package dev.shipyard.mdns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A minimal mDNS responder (RFC 6762) for the .local hostnames of running
 * Shipyard applications. It runs in its own container on the host network
 * (Shipyard itself is on a Docker bridge, where multicast cannot reach the
 * LAN), reads the names Shipyard writes to {@code <dir>/hostnames}, and
 * answers A queries for them with this server's address on the asking
 * client's network. New names are announced, removed ones get a goodbye
 * (TTL 0). It writes a heartbeat to {@code <dir>/status} for the UI.
 *
 * <pre>java -Dloader.main=dev.shipyard.mdns.MdnsResponder -cp shipyard.jar \
 *      org.springframework.boot.loader.launch.PropertiesLauncher /var/lib/shipyard/mdns</pre>
 *
 * Plain Java, no Spring: it starts in well under a second and needs little memory.
 */
public final class MdnsResponder {
    static final InetAddress GROUP = group();
    static final int PORT = 5353;
    static final int TTL = 120;
    private static final int TYPE_A = 1;
    private static final int TYPE_ANY = 255;
    private static final int CLASS_IN = 1;

    private final Path dir;
    private final String addressOverride;
    private volatile Set<String> names = Set.of();
    private MulticastSocket socket;
    private final Set<NetworkInterface> joined = new HashSet<>();

    MdnsResponder(Path dir, String addressOverride) {
        this.dir = dir;
        this.addressOverride = addressOverride == null || addressOverride.isBlank() ? null : addressOverride.strip();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "/var/lib/shipyard/mdns");
        new MdnsResponder(dir, System.getenv("SHIPYARD_MDNS_ADDRESS")).run();
    }

    void run() throws IOException {
        Files.createDirectories(dir);
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true); // share port 5353 with avahi-daemon, if the host runs one
        socket.bind(new InetSocketAddress(PORT));
        socket.setTimeToLive(255);
        socket.setSoTimeout(1000);
        joinInterfaces();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> announce(names, 0)));
        log("mDNS responder started; names from " + dir.resolve("hostnames"));

        byte[] buf = new byte[9000];
        long nextReload = 0;
        long nextHeartbeat = 0;
        long nextJoin = System.currentTimeMillis() + 30_000;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= nextReload) { reloadNames(); nextReload = now + 2000; }
            if (now >= nextHeartbeat) { heartbeat(); nextHeartbeat = now + 10_000; }
            if (now >= nextJoin) { joinInterfaces(); nextJoin = now + 30_000; }
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            try {
                handle(p);
            } catch (RuntimeException | IOException e) {
                // A malformed packet or a send failure must not stop the responder.
            }
        }
    }

    // ------------------------------------------------------------------ queries

    private void handle(DatagramPacket p) throws IOException {
        Query q = Query.parse(p.getData(), p.getLength());
        if (q == null || q.response()) return;
        Set<String> wanted = new LinkedHashSet<>();
        for (Question question : q.questions()) {
            if ((question.type() == TYPE_A || question.type() == TYPE_ANY) && names.contains(question.name())) wanted.add(question.name());
        }
        if (wanted.isEmpty()) return;
        List<Inet4Address> addresses = addressesFor(p.getAddress());
        if (addresses.isEmpty()) return;
        boolean legacy = p.getPort() != PORT; // a plain DNS resolver asking port 5353 directly: unicast, echo the question
        byte[] reply = legacy
                ? response(q.id(), q.questions().stream().filter(x -> wanted.contains(x.name())).toList(), wanted, addresses, 10, false)
                : response(0, List.of(), wanted, addresses, TTL, true);
        InetSocketAddress to = legacy ? new InetSocketAddress(p.getAddress(), p.getPort()) : new InetSocketAddress(GROUP, PORT);
        synchronized (this) {
            NetworkInterface iface = interfaceFor(p.getAddress());
            if (!legacy && iface != null) socket.setNetworkInterface(iface);
            socket.send(new DatagramPacket(reply, reply.length, to));
        }
    }

    /** A response with an A record per name and address. cache-flush marks these records as unique (RFC 6762 §10.2). */
    static byte[] response(int id, List<Question> echo, Set<String> names, List<Inet4Address> addresses, int ttl, boolean cacheFlush) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int answers = names.size() * addresses.size();
        u16(out, id);
        u16(out, 0x8400); // QR (response) + AA (authoritative)
        u16(out, echo.size());
        u16(out, answers);
        u16(out, 0);
        u16(out, 0);
        for (Question q : echo) {
            name(out, q.name());
            u16(out, q.type());
            u16(out, CLASS_IN);
        }
        for (String n : names) {
            for (Inet4Address a : addresses) {
                name(out, n);
                u16(out, TYPE_A);
                u16(out, CLASS_IN | (cacheFlush ? 0x8000 : 0));
                u32(out, ttl);
                u16(out, 4);
                out.writeBytes(a.getAddress());
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ names

    private void reloadNames() {
        Set<String> next = readNames(dir.resolve("hostnames"));
        Set<String> previous = names;
        if (next.equals(previous)) return;
        Set<String> added = new TreeSet<>(next);
        added.removeAll(previous);
        Set<String> removed = new TreeSet<>(previous);
        removed.removeAll(next);
        names = next;
        if (!removed.isEmpty()) { log("Withdrawing " + removed); announce(removed, 0); }
        if (!added.isEmpty()) {
            log("Publishing " + added);
            announce(added, TTL);
            // RFC 6762 §8.3: announce at least twice, one second apart.
            Thread t = new Thread(() -> { sleep(1000); announce(added, TTL); }, "mdns-announce");
            t.setDaemon(true);
            t.start();
        }
    }

    /** One .local hostname per line; anything else is ignored. */
    static Set<String> readNames(Path file) {
        Set<String> out = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String n = line.strip().toLowerCase(Locale.ROOT);
                if (n.endsWith(".local") && n.length() > ".local".length() && n.matches("[a-z0-9.-]+")) out.add(n);
            }
        } catch (IOException e) {
            // No file yet: nothing to publish.
        }
        return Collections.unmodifiableSet(out);
    }

    /** Unsolicited response on every interface, with that interface's own address. TTL 0 is a goodbye. */
    private synchronized void announce(Set<String> which, int ttl) {
        if (which.isEmpty() || socket == null) return;
        for (Map.Entry<NetworkInterface, Inet4Address> e : interfaceAddresses().entrySet()) {
            byte[] msg = response(0, List.of(), which, List.of(e.getValue()), ttl, true);
            try {
                socket.setNetworkInterface(e.getKey());
                socket.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(GROUP, PORT)));
            } catch (IOException ignored) {
                // Interface went away; it is dropped on the next scan.
            }
        }
    }

    // ------------------------------------------------------------------ addresses and interfaces

    /** This server's address on the asking client's subnet; all LAN addresses if no subnet matches. */
    private List<Inet4Address> addressesFor(InetAddress client) {
        if (addressOverride != null) {
            try {
                return List.of((Inet4Address) InetAddress.getByName(addressOverride));
            } catch (IOException | ClassCastException e) {
                return List.of();
            }
        }
        Map<NetworkInterface, Inet4Address> all = interfaceAddresses();
        for (NetworkInterface iface : all.keySet()) {
            for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address a && sameSubnet(a, client, ia.getNetworkPrefixLength())) return List.of(a);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(all.values()));
    }

    private NetworkInterface interfaceFor(InetAddress client) {
        for (NetworkInterface iface : interfaceAddresses().keySet()) {
            for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address a && sameSubnet(a, client, ia.getNetworkPrefixLength())) return iface;
            }
        }
        return null;
    }

    static boolean sameSubnet(Inet4Address a, InetAddress b, int prefix) {
        if (!(b instanceof Inet4Address) || prefix <= 0 || prefix > 32) return false;
        int x = toInt(a.getAddress());
        int y = toInt(b.getAddress());
        int mask = prefix == 32 ? -1 : ~(-1 >>> prefix);
        return (x & mask) == (y & mask);
    }

    /** LAN interfaces and their IPv4 address: up, multicast, not loopback, not Docker or VM bridges. */
    static Map<NetworkInterface, Inet4Address> interfaceAddresses() {
        Map<NetworkInterface, Inet4Address> out = new LinkedHashMap<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || !iface.supportsMulticast() || iface.isVirtual() || virtual(iface.getName())) continue;
                for (InetAddress a : Collections.list(iface.getInetAddresses())) {
                    if (a instanceof Inet4Address v4 && !v4.isLinkLocalAddress()) {
                        out.put(iface, v4);
                        break;
                    }
                }
            }
        } catch (SocketException e) {
            // no interfaces readable
        }
        return out;
    }

    static boolean virtual(String name) {
        return name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name.startsWith("virbr")
                || name.startsWith("cni") || name.startsWith("flannel") || name.startsWith("lxc");
    }

    private void joinInterfaces() {
        for (NetworkInterface iface : interfaceAddresses().keySet()) {
            if (joined.contains(iface)) continue;
            try {
                socket.joinGroup(new InetSocketAddress(GROUP, PORT), iface);
                joined.add(iface);
                log("Listening on " + iface.getName());
            } catch (IOException e) {
                log("Could not join the mDNS group on " + iface.getName() + ": " + e.getMessage());
            }
        }
    }

    private void heartbeat() {
        try {
            List<String> addrs = interfaceAddresses().entrySet().stream()
                    .map(e -> "\"" + e.getKey().getName() + " " + e.getValue().getHostAddress() + "\"").toList();
            String json = "{\"at\":" + (System.currentTimeMillis() / 1000) + ",\"names\":" + names.size()
                    + ",\"addresses\":[" + String.join(",", addrs) + "]}\n";
            Path tmp = dir.resolve(".status.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, dir.resolve("status"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // the UI shows the responder as not running
        }
    }

    // ------------------------------------------------------------------ wire format

    record Question(String name, int type, boolean unicast) {}

    record Query(int id, boolean response, List<Question> questions) {
        static Query parse(byte[] b, int len) {
            if (len < 12) return null;
            int id = r16(b, 0);
            int flags = r16(b, 2);
            int qd = r16(b, 4);
            int[] pos = {12};
            List<Question> qs = new ArrayList<>();
            for (int i = 0; i < qd && i < 64; i++) {
                String name = readName(b, len, pos);
                if (name == null || pos[0] + 4 > len) return null;
                int type = r16(b, pos[0]);
                int cls = r16(b, pos[0] + 2);
                pos[0] += 4;
                qs.add(new Question(name.toLowerCase(Locale.ROOT), type, (cls & 0x8000) != 0));
            }
            return new Query(id, (flags & 0x8000) != 0, qs);
        }
    }

    /** Reads a (possibly compressed) name at pos[0], advancing it past the name. */
    static String readName(byte[] b, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean jumped = false;
        for (int hops = 0; hops < 32; hops++) {
            if (p >= len) return null;
            int l = b[p] & 0xff;
            if (l == 0) {
                if (!jumped) pos[0] = p + 1;
                return sb.toString();
            }
            if ((l & 0xc0) == 0xc0) {
                if (p + 1 >= len) return null;
                if (!jumped) pos[0] = p + 2;
                p = ((l & 0x3f) << 8) | (b[p + 1] & 0xff);
                jumped = true;
                continue;
            }
            if (p + 1 + l > len) return null;
            if (!sb.isEmpty()) sb.append('.');
            sb.append(new String(b, p + 1, l, StandardCharsets.UTF_8));
            p += 1 + l;
        }
        return null;
    }

    private static void name(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.UTF_8);
            out.write(l.length);
            out.writeBytes(l);
        }
        out.write(0);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        u16(out, (v >> 16) & 0xffff);
        u16(out, v & 0xffff);
    }

    static int r16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int toInt(byte[] a) {
        return ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
    }

    private static InetAddress group() {
        try {
            return InetAddress.getByName("224.0.0.251");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        System.out.println("[mdns] " + msg);
    }
}
