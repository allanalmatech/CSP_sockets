import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "Live" TCP time server: after a client connects, the server keeps PUSHING
 * the current system time once per interval instead of sending it once and
 * hanging up.
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket() -> bind() -> listen()
 *   -> [ accept() -> [ send() ... send() ] -> closesocket() ]*   (one thread)
 *   -> closesocket()
 *
 * Difference from TimeServer: the connection stays OPEN, so the server must be
 * CONCURRENT, not iterative. An iterative server would be stuck inside the inner
 * send-loop of the first client and could never accept the second one. Here
 * every client gets its own short-lived thread.
 */
public class LiveTimeServer {

    private static final int DEFAULT_PORT = 6000;
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    /** How often a new timestamp is pushed, in milliseconds. */
    private static final long DEFAULT_INTERVAL_MS = 1000;
    private static final long MIN_INTERVAL_MS = 100;
    private static final long MAX_INTERVAL_MS = 60_000;

    /**
     * Upper bound on how long we block waiting to notice that a client has
     * gone away. This is the "poll" timeout, NOT the update interval: it lets
     * the server notice a disconnect quickly instead of pushing timestamps
     * into a dead socket until the OS finally complains.
     */
    private static final int POLL_TIMEOUT_MS = 200;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public static void main(String[] args) {
        // ---- Step 0: parse argv[1] (port) and argv[2] (interval in ms) ------
        int port;
        try {
            port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be a number, got \"" + args[0] + "\".");
            System.err.println("Usage: java LiveTimeServer [port] [interval-ms]");
            System.exit(1);
            return;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            System.err.println("Error: port " + port + " is out of range. Allowed range is "
                    + MIN_PORT + "-" + MAX_PORT + ".");
            System.exit(1);
            return;
        }

        long intervalMs;
        try {
            intervalMs = (args.length > 1) ? Long.parseLong(args[1]) : DEFAULT_INTERVAL_MS;
        } catch (NumberFormatException e) {
            System.err.println("Error: interval must be a number, got \"" + args[1] + "\".");
            System.exit(1);
            return;
        }

        if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS) {
            System.err.println("Error: interval must be between " + MIN_INTERVAL_MS
                    + " and " + MAX_INTERVAL_MS + " ms, got " + intervalMs + ".");
            System.exit(1);
            return;
        }

        // try-with-resources: the listening socket closes automatically.
        try (ServerSocket serverSocket = new ServerSocket()) {

            // ---- Step 1: socket() + setsockopt(SO_REUSEADDR) ----------------
            serverSocket.setReuseAddress(true);

            // ---- Step 2: bind() ---------------------------------------------
            // 0.0.0.0 / INADDR_ANY => reachable from the whole network.
            serverSocket.bind(new InetSocketAddress(port));

            // ---- Step 3: listen() (implicit in the bind) ---------------------

            System.out.println("LiveTimeServer streaming on port " + port
                    + " (bound to " + serverSocket.getInetAddress().getHostAddress() + ")");
            System.out.println("Update interval: " + intervalMs + " ms");
            System.out.println("Press Ctrl+C to stop.");

            // ---- Step 4: accept() loop, forever ------------------------------
            while (true) {
                try {
                    // accept() blocks until a client connects and returns a new,
                    // already-connected socket.
                    Socket clientSocket = serverSocket.accept();
                    InetSocketAddress remote =
                            (InetSocketAddress) clientSocket.getRemoteSocketAddress();

                    System.out.println("[+] Client connected: "
                            + remote.getAddress().getHostAddress()
                            + ":" + remote.getPort());

                    // One thread per client. Daemon = it will not keep the JVM
                    // alive on its own; the accept() loop is the anchor.
                    Thread worker = new Thread(
                            () -> streamTime(clientSocket, intervalMs), "client-remote");
                    worker.setDaemon(true);
                    worker.start();

                } catch (IOException e) {
                    System.err.println("[!] Error accepting a client: " + e.getMessage());
                }
            }

        } catch (BindException e) {
            System.err.println("Error: cannot bind to the requested port - " + e.getMessage());
            System.err.println("Another program is probably already listening on that port.");
            System.exit(1);
        } catch (SocketException e) {
            System.err.println("Error: socket problem - " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: I/O problem - " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Streams timestamps to one client until that client disconnects.
     * Runs on its own thread; the socket is closed automatically on exit.
     */
    private static void streamTime(Socket socket, long intervalMs) {
        String clientLabel = "client";

        // try-with-resources: Socket, Writer and BufferedReader all get closed
        // here, so no client can ever leak a socket.
        try (Socket clientSocket = socket;
             Writer out = new OutputStreamWriter(
                     clientSocket.getOutputStream(), StandardCharsets.UTF_8);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

            InetSocketAddress remote =
                    (InetSocketAddress) clientSocket.getRemoteSocketAddress();
            clientLabel = remote.getAddress().getHostAddress() + ":" + remote.getPort();

            // Poll for a disconnect at least as often as we send updates.
            clientSocket.setSoTimeout((int) Math.max(1, Math.min(POLL_TIMEOUT_MS, intervalMs)));

            // ---- Step 5: send() in a loop, until the client goes away -------
            long nextSendAt = 0L;

            while (!Thread.currentThread().isInterrupted()) {

                long now = System.currentTimeMillis();
                if (now >= nextSendAt) {
                    String message = currentTime();
                    // In C: send(client, msg, strlen(msg), 0);
                    // "\n" keeps it a readable line; the client can print each
                    // update in place for a ticking live clock.
                    out.write(message);
                    out.write("\n");
                    out.flush();
                    nextSendAt = now + intervalMs;
                }

                // The client never sends us anything, so any read here is
                // purely a disconnect detector:
                //   -1 / EOF        => the client closed the socket cleanly
                //   timeout         => still connected, nothing to do
                //   any other data  => ignored on purpose
                try {
                    int received = in.read();
                    if (received == -1) {
                        System.out.println("[-] Client disconnected: " + clientLabel);
                        return;
                    }
                } catch (SocketTimeoutException expected) {
                    // No data and no disconnect - completely normal, keep going.
                }
            }

        } catch (IOException e) {
            // Broken pipe / connection reset: the client vanished mid-stream.
            // This is the expected end of a stream, not a server failure.
            System.out.println("[-] Stream ended for " + clientLabel
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Formats the current local time in the agreed "readable" layout. */
    static String currentTime() {
        return TIME_FORMAT.format(ZonedDateTime.now(ZoneId.systemDefault()));
    }
}
