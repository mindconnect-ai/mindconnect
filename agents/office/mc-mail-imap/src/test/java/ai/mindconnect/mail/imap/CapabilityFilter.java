package ai.mindconnect.mail.imap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An IMAP server that lacks what GreenMail has: a proxy in front of it that
 * takes capabilities out of every CAPABILITY line on the way to the client.
 *
 * <p>GreenMail announces MOVE and UIDPLUS and cannot be told not to. Plenty
 * of servers in the wild have neither, and the client has to find out from
 * what the server announced — so that is the one thing changed here. The
 * commands still reach GreenMail as they were sent; a client that sent MOVE
 * anyway would get away with it, but Angus refuses to send what was not
 * announced, which is exactly the failure this stands in for.
 */
final class CapabilityFilter implements AutoCloseable {

    private final ServerSocket listening;
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    CapabilityFilter(int imapPort, Set<String> dropped) throws IOException {
        this.listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!listening.isClosed()) {
                try {
                    Socket client = listening.accept();
                    Socket server = new Socket(InetAddress.getLoopbackAddress(), imapPort);
                    open.add(client);
                    open.add(server);
                    pump(client.getInputStream(), server.getOutputStream(), null);
                    pump(server.getInputStream(), client.getOutputStream(), dropped);
                } catch (IOException e) {
                    // Closed, or the test is over.
                }
            }
        }, "capability-filter");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return listening.getLocalPort();
    }

    /** Copies line by line; a CAPABILITY line loses the {@code dropped} words when there are any. */
    private static void pump(InputStream in, OutputStream out, Set<String> dropped) {
        Thread copier = new Thread(() -> {
            try (in; out) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) >= 0) {
                    line.write(b);
                    if (b != '\n') continue;
                    byte[] bytes = line.toByteArray();
                    line.reset();
                    if (dropped != null) {
                        String text = new String(bytes, StandardCharsets.ISO_8859_1);
                        if (text.contains("CAPABILITY")) {
                            for (String word : dropped) text = text.replace(" " + word, "");
                            bytes = text.getBytes(StandardCharsets.ISO_8859_1);
                        }
                    }
                    out.write(bytes);
                    out.flush();
                }
            } catch (IOException e) {
                // One side hung up.
            }
        }, "capability-filter-pump");
        copier.setDaemon(true);
        copier.start();
    }

    @Override
    public void close() throws IOException {
        listening.close();
        for (Socket socket : open) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Going away either way.
            }
        }
    }
}
