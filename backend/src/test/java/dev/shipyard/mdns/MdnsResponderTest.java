package dev.shipyard.mdns;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MdnsResponderTest {
    @TempDir Path dir;

    /** A query as a client sends it: one question, optionally with the unicast-response bit. */
    static byte[] query(String name, int type, boolean qu) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String l : name.split("\\.")) {
            out.write(l.length());
            out.writeBytes(l.getBytes());
        }
        out.write(0);
        out.writeBytes(new byte[] {0, (byte) type, (byte) (qu ? 0x80 : 0), 1});
        return out.toByteArray();
    }

    @Test
    void parsesQuestions() {
        byte[] q = query("DevStick.local", 1, true);
        MdnsResponder.Query parsed = MdnsResponder.Query.parse(q, q.length);
        assertThat(parsed.response()).isFalse();
        assertThat(parsed.questions()).containsExactly(new MdnsResponder.Question("devstick.local", 1, true));
    }

    @Test
    void followsNameCompression() {
        // Second question "app.local" written as "app" + pointer to "local" inside the first name (offset 12 + 1 + 8 = 21).
        byte[] first = query("devstick.local", 1, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(first);
        out.writeBytes(new byte[] {3, 'a', 'p', 'p', (byte) 0xc0, 21, 0, 1, 0, 1});
        byte[] b = out.toByteArray();
        b[5] = 2; // two questions
        MdnsResponder.Query parsed = MdnsResponder.Query.parse(b, b.length);
        assertThat(parsed.questions()).extracting(MdnsResponder.Question::name).containsExactly("devstick.local", "app.local");
    }

    @Test
    void answersWithAnARecordPerAddress() throws Exception {
        Inet4Address ip = (Inet4Address) InetAddress.getByName("192.168.1.20");
        byte[] r = MdnsResponder.response(0, List.of(), Set.of("devstick.local"), List.of(ip), 120, true);
        assertThat(MdnsResponder.r16(r, 2)).isEqualTo(0x8400);  // response, authoritative
        assertThat(MdnsResponder.r16(r, 6)).isEqualTo(1);       // one answer
        int[] pos = {12};
        assertThat(MdnsResponder.readName(r, r.length, pos)).isEqualTo("devstick.local");
        int p = pos[0];
        assertThat(MdnsResponder.r16(r, p)).isEqualTo(1);            // A
        assertThat(MdnsResponder.r16(r, p + 2)).isEqualTo(0x8001);   // IN + cache-flush
        assertThat((MdnsResponder.r16(r, p + 4) << 16) | MdnsResponder.r16(r, p + 6)).isEqualTo(120);
        assertThat(MdnsResponder.r16(r, p + 8)).isEqualTo(4);
        assertThat(InetAddress.getByAddress(java.util.Arrays.copyOfRange(r, p + 10, p + 14))).isEqualTo(ip);
    }

    @Test
    void goodbyeHasTtlZero() throws Exception {
        Inet4Address ip = (Inet4Address) InetAddress.getByName("10.0.0.2");
        byte[] r = MdnsResponder.response(0, List.of(), Set.of("a.local"), List.of(ip), 0, true);
        int[] pos = {12};
        MdnsResponder.readName(r, r.length, pos);
        assertThat((MdnsResponder.r16(r, pos[0] + 4) << 16) | MdnsResponder.r16(r, pos[0] + 6)).isZero();
    }

    @Test
    void picksTheAddressOnTheClientsSubnet() throws Exception {
        Inet4Address lan = (Inet4Address) InetAddress.getByName("192.168.1.20");
        assertThat(MdnsResponder.sameSubnet(lan, InetAddress.getByName("192.168.1.77"), 24)).isTrue();
        assertThat(MdnsResponder.sameSubnet(lan, InetAddress.getByName("192.168.2.77"), 24)).isFalse();
        assertThat(MdnsResponder.sameSubnet(lan, InetAddress.getByName("192.168.2.77"), 16)).isTrue();
        assertThat(MdnsResponder.virtual("docker0")).isTrue();
        assertThat(MdnsResponder.virtual("br-1a2b")).isTrue();
        assertThat(MdnsResponder.virtual("eth0")).isFalse();
    }

    @Test
    void readsOnlyLocalNames() throws Exception {
        Path f = Files.writeString(dir.resolve("hostnames"), "devstick.local\nApp.Local\nexample.com\n.local\nbad name.local\n\n");
        assertThat(MdnsResponder.readNames(f)).containsExactly("app.local", "devstick.local");
        assertThat(MdnsResponder.readNames(dir.resolve("missing"))).isEmpty();
    }
}
