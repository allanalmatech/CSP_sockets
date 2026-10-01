import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * UDP client for the {@link UdpTimeServer} (bonus).
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket(AF_INET, SOCK_DGRAM) -> sendto() -> recvfrom() -> closesocket()
 *
 * Note: there is no connect() and no handshake, so a wrong address gives a
 * timeout instead of "connection refused".
 *
 * Usage:  java UdpTimeClient <server-host> [port]
 */
public class UdpTimeClient {

    private static final int DEFAULT_PORT = 6000;
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    private static final int BUFFER_SIZE = 1024;

    /**
     * UDP has no handshake, so we must set a receive timeout ourselves -
     * otherwise recvfrom() would block forever if the reply never arrives.
     */
    private static final int TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        // ---- Step 0: parse argv[1] (host) and argv[2] (port) ---------------
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java UdpTimeClient <server-host> [port]");
            System.err.println("Example: java UdpTimeClient localhost " + DEFAULT_PORT);
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

        // try-with-resources closes the socket in every case.
        try (DatagramSocket socket = new DatagramSocket()) {

            // ---- Step 1: socket() -------------------------------------------
            // No bind() needed: the OS assigns a random local port for us.

            // Only a client that sends a *named* datagram can receive a reply,
            // so the hostname must be resolved explicitly. This replaces
            // gethostbyname() and throws UnknownHostException on failure.
            InetAddress serverAddress = InetAddress.getByName(host);
            socket.setSoTimeout(TIMEOUT_MS);

            System.out.println("[+] Local port " + socket.getLocalPort()
                    + "  ->  sending request to " + serverAddress.getHostAddress()
                    + ":" + port);

            // ---- Step 2: sendto() -------------------------------------------
            // In C:  sendto(s, req, strlen(req), 0,
            //                (struct sockaddr*)&server, sizeof(server));
            byte[] request = "TIME?\n".getBytes(StandardCharsets.UTF_8);
            DatagramPacket requestPacket =
                    new DatagramPacket(request, request.length, serverAddress, port);
            socket.send(requestPacket);

            // ---- Step 3: recvfrom() -----------------------------------------
            byte[] buffer = new byte[BUFFER_SIZE];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);

            String message = new String(reply.getData(), reply.getOffset(),
                    reply.getLength(), StandardCharsets.UTF_8).trim();

            // ---- Step 4: print the result -----------------------------------
            System.out.println("Reply from " + reply.getAddress().getHostAddress()
                    + ":" + reply.getPort());
            System.out.println("Server time: " + message);

        } catch (UnknownHostException e) {
            System.err.println("Error: cannot resolve host \"" + host
                    + "\" - check the spelling, or that machine is on the network/DNS.");
            System.exit(1);
        } catch (SocketTimeoutException e) {
            // This is the UDP equivalent of "connection refused": nothing
            // complained, we simply never heard back.
            System.err.println("Error: no reply from " + host + ":" + port + " within "
                    + TIMEOUT_MS + " ms.");
            System.err.println("The server may be down, the port may be blocked, or UDP may be filtered.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: I/O problem - " + e.getMessage());
            System.exit(1);
        }
    }
}
