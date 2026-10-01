import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * TCP "time server" (iterative / single-threaded).
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket() -> bind() -> listen() -> [ accept() -> send() -> closesocket() ]*
 *   -> closesocket()
 *
 * Java hides WSAStartup/WSACleanup and the manual htons()/htonl() byte-order
 * conversion, so we only explicitly create the server (listening) socket,
 * bind it and listen on it.
 */
public class TimeServer {

    /** Default port when the user does not pass one (assignment minimum). */
    private static final int DEFAULT_PORT = 6000;

    /** Lowest / highest port the assignment allows. */
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    /** "yyyy-MM-dd HH:mm:ss z" -> 2026-10-01 14:32:05 EAT */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public static void main(String[] args) {
        int port;

        // ---- Step 0: parse and validate argv[1] (the port) -------------------
        try {
            port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be a number, got \"" + args[0] + "\".");
            System.err.println("Usage: java TimeServer [port]   (port range " + MIN_PORT + "-" + MAX_PORT + ")");
            System.exit(1);
            return;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            System.err.println("Error: port " + port + " is out of range. Allowed range is "
                    + MIN_PORT + "-" + MAX_PORT + ".");
            System.err.println("Usage: java TimeServer [port]");
            System.exit(1);
            return;
        }

        // try-with-resources: the listening socket is closed automatically,
        // even if an exception escapes the block.
        try (ServerSocket serverSocket = new ServerSocket()) {

            // ---- Step 1: socket() + setsockopt(SO_REUSEADDR) ----------------
            // In C:  int s = socket(AF_INET, SOCK_STREAM, 0);
            //         setsockopt(s, SOL_SOCKET, SO_REUSEADDR, ...);
            // new ServerSocket() creates an *unbound* TCP socket.
            serverSocket.setReuseAddress(true);

            // ---- Step 2: bind() ---------------------------------------------
            // In C:  struct sockaddr_in addr; addr.sin_family = AF_INET;
            //         addr.sin_port = htons(port);
            //         addr.sin_addr.s_addr = htonl(INADDR_ANY);
            //         bind(s, (struct sockaddr*)&addr, sizeof(addr));
            // INADDR_ANY => accept connections on every local interface
            // (0.0.0.0), so other machines in the network can reach us.
            // Java performs the htons()/htonl() conversion for us.
            serverSocket.bind(new InetSocketAddress(port));

            // ---- Step 3: listen() -------------------------------------------
            // In C:  listen(s, BACKLOG);
            // Java has no listen() method: the backlog (the OS accept queue
            // size) is a constructor argument, and the JVM default of 50
            // already applies to the bind() above. To see it explicitly you
            // would instead write:
            //     new ServerSocket(port, BACKLOG)   // socket()+bind()+listen
            // which performs Steps 1-3 in a single call.
            //
            // From here on the socket is passive: it only accepts connections,
            // it never sends or receives data itself.

            System.out.println("TimeServer listening on port " + port
                    + " (bound to " + serverSocket.getInetAddress().getHostAddress() + ")");
            System.out.println("Press Ctrl+C to stop.");

            // ---- Step 4: accept() loop, forever ------------------------------
            // Iterative server: handle one client completely, then loop back
            // to accept() the next one. No threads are needed.
            while (true) {
                // accept() blocks until a client connects, then returns a NEW
                // socket that is already connected to that one client.
                try (Socket clientSocket = serverSocket.accept()) {

                    InetSocketAddress remote =
                            (InetSocketAddress) clientSocket.getRemoteSocketAddress();

                    // Log the client IP address and ephemeral port.
                    System.out.println("[+] Client connected: "
                            + remote.getAddress().getHostAddress()
                            + ":" + remote.getPort()
                            + "  (local port " + clientSocket.getLocalPort() + ")");

                    // Build the timestamp ON the server machine.
                    String message = currentTime();

                    // ---- Step 5: send() ---------------------------------------
                    // In C:  send(client, msg, strlen(msg), 0);
                    // Write text as UTF-8; a trailing "\n" makes the output
                    // human-readable in a terminal / telnet session.
                    try (OutputStreamWriter osw =
                                 new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8);
                         BufferedWriter out = new BufferedWriter(osw)) {
                        out.write(message);
                        out.newLine();
                        out.flush();
                    }

                    System.out.println("    -> Sent: \"" + message + "\"");

                } catch (IOException e) {
                    // Never let one bad client kill the whole server.
                    System.err.println("[!] Error while serving a client: " + e.getMessage());
                }
            }

        } catch (BindException e) {
            // Port already in use, or no permission for that port.
            System.err.println("Error: cannot bind to the requested port - "
                    + e.getMessage());
            System.err.println("Another program (or another copy of this server) is probably "
                    + "already listening on that port.");
            System.exit(1);
        } catch (SocketException e) {
            // e.g. the socket was closed, or too many open files.
            System.err.println("Error: socket problem - " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: I/O problem - " + e.getMessage());
            System.exit(1);
        }
    }

    /** Formats the current local time in the agreed "readable" layout. */
    static String currentTime() {
        return TIME_FORMAT.format(ZonedDateTime.now(ZoneId.systemDefault()));
    }
}
