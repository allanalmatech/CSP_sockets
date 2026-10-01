import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * UDP version of the time server (bonus - compare with the TCP version).
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket(AF_INET, SOCK_DGRAM) -> bind()
 *   -> [ recvfrom() -> sendto() ]*   -> closesocket()
 *
 * There is NO listen() and NO accept(): UDP is connectionless, so the one
 * socket both receives requests and sends replies. It also has no stream, so
 * each DatagramPacket must carry a whole message by itself.
 */
public class UdpTimeServer {

    private static final int DEFAULT_PORT = 6000;
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    /** UDP datagrams are small; 1024 bytes is plenty for a time string. */
    private static final int BUFFER_SIZE = 1024;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public static void main(String[] args) {
        // ---- Step 0: parse and validate the port ---------------------------
        int port;
        try {
            port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be a number, got \"" + args[0] + "\".");
            System.exit(1);
            return;
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            System.err.println("Error: port " + port + " is out of range. Allowed range is "
                    + MIN_PORT + "-" + MAX_PORT + ".");
            System.exit(1);
            return;
        }

        // try-with-resources closes the single UDP socket for us.
        // NOTE: the (InetSocketAddress) null constructor argument is required -
        // plain "new DatagramSocket()" auto-binds a random ephemeral port, and
        // calling bind() on an already-bound socket throws
        // "SocketException: Already bound". null means "create it unbound".
        try (DatagramSocket socket = new DatagramSocket((InetSocketAddress) null)) {

            socket.setReuseAddress(true);

            // ---- Step 1: bind() ---------------------------------------------
            // In C:  bind(s, (struct sockaddr*)&addr, sizeof(addr));
            // Binding pins the well-known port; without it the OS picks a
            // random ephemeral port and no client could ever find us.
            socket.bind(new InetSocketAddress(port));

            byte[] buffer = new byte[BUFFER_SIZE];

            System.out.println("UdpTimeServer listening on UDP port " + port
                    + " (bound to " + socket.getLocalAddress().getHostAddress() + ")");
            System.out.println("Press Ctrl+C to stop.");

            // ---- Step 2: receive loop, forever ------------------------------
            while (true) {
                // A DatagramPacket needs a buffer + length to receive into.
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);

                // ---- Step 3: recvfrom() ------------------------------------
                // Blocks until a datagram arrives, then fills in the packet
                // with the sender's IP/port (source address).
                socket.receive(request);

                InetAddress clientAddress = request.getAddress();
                int clientPort = request.getPort();

                String requestText =
                        new String(request.getData(), request.getOffset(),
                                request.getLength(), StandardCharsets.UTF_8).trim();
                System.out.println("[+] Request from " + clientAddress.getHostAddress()
                        + ":" + clientPort + "  payload=\"" + requestText + "\"");

                // Build the timestamp ON the server machine.
                String message = TIME_FORMAT.format(ZonedDateTime.now(ZoneId.systemDefault()));
                byte[] reply = (message + "\n").getBytes(StandardCharsets.UTF_8);

                // ---- Step 4: sendto() --------------------------------------
                // The reply is addressed to the sender's source port, which is
                // how the client knows where to listen.
                DatagramPacket response =
                        new DatagramPacket(reply, reply.length, clientAddress, clientPort);
                socket.send(response);

                System.out.println("    -> Sent: \"" + message + "\"");

                // No connection to close: UDP sockets are shared by all
                // clients, so we simply loop back to receive().
            }

        } catch (SocketException e) {
            System.err.println("Error: cannot open the UDP socket - " + e.getMessage());
            System.err.println("If the message is \"Address already in use\" then another "
                    + "program holds that UDP port; if it is \"Already bound\" then bind() "
                    + "was called twice on the same socket. A firewall may also block UDP.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: I/O problem - " + e.getMessage());
            System.exit(1);
        }
    }
}
