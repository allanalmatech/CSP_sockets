import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * TCP client for the {@link TimeServer}.
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket() -> connect() -> recv() -> closesocket()
 *
 * Usage:  java TimeClient <server-host> [port]
 *         java TimeClient 192.168.1.10 6000
 *         java TimeClient localhost 6000
 */
public class TimeClient {

    private static final int DEFAULT_PORT = 6000;
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    /** Generous, but bounded so a wrong address cannot hang the program. */
    private static final int TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        // ---- Step 0: parse argv[1] (host) and argv[2] (port) ---------------
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java TimeClient <server-host> [port]");
            System.err.println("Example: java TimeClient localhost " + DEFAULT_PORT);
            System.exit(1);
            return;
        }

        String host = args[0];

        int port;
        try {
            port = (args.length > 1) ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be a number, got \"" + args[1] + "\".");
            System.exit(1);
            return;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            System.err.println("Error: port " + port + " is out of range. Allowed range is "
                    + MIN_PORT + "-" + MAX_PORT + ".");
            System.exit(1);
            return;
        }

        // try-with-resources: Socket, BufferedReader and InputStreamReader
        // are all closed automatically, even on an exception.
        try (Socket socket = new Socket()) {

            // ---- Step 1: socket() -------------------------------------------
            // new Socket() creates a plain unconnected TCP socket.
            // (In C this is socket(AF_INET, SOCK_STREAM, 0).)

            // Connect with a timeout instead of blocking forever.
            // In C:  connect(s, (struct sockaddr*)&server, sizeof(server));
            // A hostname is resolved here - this replaces gethostbyname()/
            // getaddrinfo(), and throws UnknownHostException if DNS fails.
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);

            // Optional: how long we are willing to wait for data.
            socket.setSoTimeout(TIMEOUT_MS);

            InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            System.out.println("[+] Connected to " + remote.getAddress().getHostAddress()
                    + ":" + remote.getPort() + "  (from local port " + local.getPort() + ")");

            // ---- Step 2: recv() --------------------------------------------
            // In C:  n = recv(s, buf, sizeof(buf), 0);
            // The server closes the connection after sending one line, so
            // readLine() returns and then hits end-of-stream.
            try (InputStreamReader isr =
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(isr)) {

                String line = in.readLine();
                if (line == null) {
                    System.err.println("Server closed the connection without sending any data.");
                } else {
                    // ---- Step 3: print the result ----------------------------
                    System.out.println("Server time: " + line);
                }
            }

        } catch (UnknownHostException e) {
            // DNS lookup failed / host has no such name. Note that
            // getMessage() only echoes the hostname, so add the real hint here.
            System.err.println("Error: cannot resolve host \"" + host
                    + "\" - check the spelling, or that machine is on the network/DNS.");
            System.exit(1);
        } catch (java.net.ConnectException e) {
            // Nothing is listening on that host:port (server not started,
            // wrong port, or blocked by a firewall).
            System.err.println("Error: connection refused by " + host + ":" + port
                    + " - is the server running?");
            System.exit(1);
        } catch (SocketTimeoutException e) {
            System.err.println("Error: timed out after " + TIMEOUT_MS
                    + " ms while connecting or reading from " + host + ":" + port);
            System.exit(1);
        } catch (IOException e) {
            // Network unreachable, connection reset, permissions, ...
            System.err.println("Error: I/O problem - " + e.getMessage());
            System.exit(1);
        }
    }
}
