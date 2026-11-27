package dev.shipyard.caddy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Minimal HTTP/1.1 client over a Unix domain socket, used to talk to Caddy's
 * admin API without exposing it on the network that hosted applications share.
 */
final class UnixSocketHttp {
    record Response(int status, String body) {}

    private UnixSocketHttp() {}

    static Response post(Path socket, String path, String contentType, byte[] body) throws IOException {
        try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            ch.write(ByteBuffer.wrap(head.getBytes(StandardCharsets.US_ASCII)));
            ByteBuffer b = ByteBuffer.wrap(body);
            while (b.hasRemaining()) ch.write(b);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            while (ch.read(buf) > 0) {
                buf.flip();
                out.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
            String raw = out.toString(StandardCharsets.UTF_8);
            int sp = raw.indexOf(' ');
            int status = sp > 0 && raw.length() >= sp + 4 ? Integer.parseInt(raw.substring(sp + 1, sp + 4)) : 0;
            int split = raw.indexOf("\r\n\r\n");
            return new Response(status, split >= 0 ? raw.substring(split + 4) : "");
        }
    }
}
