import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;

/**
 * Client for the {@link LiveTimeServer}: prints the server's time, refreshed
 * live, until the user presses Ctrl+C.
 *
 * Algorithm in C/WinSock terms:
 *   WSAStartup() -> socket() -> connect() -> [ recv() ... recv() ] -> closesocket()
 *
 * It REMEMBERS the server address in config.txt next to this program, so on
 * the next run it does not have to ask again:
 *
 *   config.txt
 *   --------
 *   host=192.168.56.1
 *   port=6000
 *
 * Usage:
 *   java LiveTimeClient                 # load host from config.txt, ask if absent
 *   java LiveTimeClient <host> [port]   # override, and remember it for next time
 *   java LiveTimeClient --forget        # delete config.txt so it asks again
 */
public class LiveTimeClient {

    /** The memory file. Same folder as the program by default. */
    private static final String CONFIG_FILE = "config.txt";

    private static final int DEFAULT_PORT = 6000;
    private static final int MIN_PORT = 6000;
    private static final int MAX_PORT = 65535;

    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** Give up if no update arrives for this long (e.g. server died). */
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * Lazily created reader for the interactive prompts. Kept as a field so it
     * is created only once and can be prompted repeatedly.
     */
    private static BufferedReader stdin;

    public static void main(String[] args) {
        Path configPath = Path.of(CONFIG_FILE);

        // ---- Step 0: work out which server to talk to ---------------------
        if (args.length > 0 && "--forget".equals(args[0])) {
            forget(configPath);
            return;
        }

        String host;
        int port;
        // True when the address came from memory/arguments rather than a fresh
        // prompt - that is what makes a stale config.txt confusing.
        boolean remembered;

        if (args.length > 0) {
            // Explicit host given on the command line: use it and remember it.
            host = args[0].trim();
            if (host.isEmpty()) {
                System.err.println("Error: the server address cannot be empty.");
                System.err.println("Usage: java LiveTimeClient <host> [port]");
                System.exit(1);
                return;
            }
            port = parsePort(args.length > 1 ? args[1] : String.valueOf(DEFAULT_PORT));
            if (port < 0) {
                return;
            }
            remember(configPath, host, port);
            System.out.println("[i] Remembering " + host + ":" + port + " in " + CONFIG_FILE);
            remembered = true;
        } else {
            // No arguments: try to recall the server from config.txt.
            String[] saved = recall(configPath);
            if (saved == null) {
                // First run (or the file was deleted) - ask the user.
                System.out.println("[i] No server address saved yet in " + CONFIG_FILE + ".");
                host = askHost();
                if (host == null) {
                    System.err.println("Error: no server address entered.");
                    System.exit(1);
                    return;
                }
                port = askPort();
                if (port < 0) {
                    return;
                }
                remember(configPath, host, port);
                System.out.println("[i] Saved to " + CONFIG_FILE + " - next time I will not ask again.");
                remembered = false;
            } else {
                host = saved[0];
                port = parsePort(saved[1]);
                if (port < 0) {
                    return;
                }
                System.out.println("[i] Using remembered server " + host + ":" + port
                        + " from " + CONFIG_FILE);
                remembered = true;
            }
        }

        // ---- Steps 1-3: connect and stream ---------------------------------
        // If the connection cannot be made, do NOT just give up: a remembered
        // config.txt from an earlier test is the most common reason, so offer
        // to enter a different address and save it.
        while (true) {
            String failure = stream(host, port);

            if (failure == null) {
                // The stream ended normally, so we are done.
                return;
            }

            System.err.println();
            System.err.println("Error: " + failure);
            if (remembered) {
                System.err.println("That address came from " + CONFIG_FILE
                        + ", so an old or incorrect value may be remembered.");
            }
            System.err.println();

            System.out.println("[i] Enter a different address, or press Ctrl+C to quit.");
            String newHost = askHost();
            if (newHost == null) {
                System.err.println("No new address entered - giving up.");
                System.err.println("Tip: run \"java LiveTimeClient --forget\" to clear "
                        + CONFIG_FILE + ".");
                System.exit(1);
                return;
            }
            int newPort = askPort();
            if (newPort < 0) {
                return;
            }

            remember(configPath, newHost, newPort);
            System.out.println("[i] Retrying with " + newHost + ":" + newPort + " ...");
            host = newHost;
            port = newPort;
            remembered = true;
        }
    }

    /**
     * Connects to {@code host:port} and streams the time until the connection
     * ends.
     *
     * @return {@code null} when the stream ended normally (the server closed
     *         the connection); otherwise a human-readable message describing
     *         why the connection could not be established.
     */
    private static String stream(String host, int port) {
        // ---- Step 1: socket() -----------------------------------------------
        // try-with-resources guarantees the socket is closed on every path,
        // including the failure paths that return an error message.
        try (Socket socket = new Socket()) {

            // ---- Step 2: connect() ------------------------------------------
            // Replaces connect(); resolves the hostname for us (the job of
            // gethostbyname()) and throws UnknownHostException if that fails.
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            System.out.println("[+] Connected to " + remote.getAddress().getHostAddress()
                    + ":" + remote.getPort() + " - streaming time, press Ctrl+C to stop.");
            System.out.println();

            // ---- Step 3: recv() in a loop ------------------------------------
            // Replaces recv(); the server keeps sending, so we keep reading
            // until the connection is closed or the read times out.
            try (Reader r = new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8);
                 BufferedReader in = new BufferedReader(r)) {

                int updates = 0;
                while (true) {
                    String line = in.readLine();
                    if (line == null) {
                        // EOF: the server closed the stream.
                        System.out.println();
                        if (updates == 0) {
                            System.out.println("[-] The server closed the connection without sending "
                                    + "any updates.");
                            System.out.println("    This is the behaviour of the one-shot TimeServer. "
                                    + "Make sure LiveTimeServer (not TimeServer) is running on port "
                                    + port + ".");
                        } else {
                            System.out.println("[-] Server closed the connection after "
                                    + updates + " update(s).");
                        }
                        return null;
                    }
                    updates++;
                    // Carriage return ("\r") rewrites the same terminal line, so
                    // the timestamp visibly ticks instead of scrolling away.
                    System.out.print("\rServer time: " + line + "   ");
                    System.out.flush();
                }
            }

        } catch (UnknownHostException e) {
            return "cannot resolve host \"" + host + "\" - check the spelling";
        } catch (ConnectException e) {
            return "connection refused by " + host + ":" + port
                    + " - is LiveTimeServer running on that machine?";
        } catch (SocketTimeoutException e) {
            return "no update received for " + READ_TIMEOUT_MS
                    + " ms - connected, but the server sent nothing";
        } catch (IOException e) {
            return "I/O problem - " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // config.txt handling
    // ------------------------------------------------------------------

    /**
     * Reads host and port from config.txt, or returns null if the file is
     * missing or does not contain a host yet.
     */
    private static String[] recall(Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            System.err.println("[!] Could not read " + CONFIG_FILE + ": " + e.getMessage()
                    + " - asking again.");
            return null;
        }

        String host = props.getProperty("host", "").trim();
        if (host.isEmpty()) {
            return null;
        }
        String port = props.getProperty("port", String.valueOf(DEFAULT_PORT)).trim();
        return new String[]{host, port};
    }

    /** Writes host and port to config.txt so the next run can skip the prompt. */
    private static void remember(Path configPath, String host, int port) {
        Properties props = new Properties();
        props.setProperty("host", host);
        props.setProperty("port", String.valueOf(port));
        try (Writer w = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            props.store(w, "Remembered LiveTimeServer address. Edit or delete this file to change it.");
        } catch (IOException e) {
            System.err.println("[!] Could not write " + CONFIG_FILE + ": " + e.getMessage()
                    + " (you will be asked again next time).");
        }
    }

    /** Deletes config.txt so the next run asks for the server again. */
    private static void forget(Path configPath) {
        try {
            if (Files.deleteIfExists(configPath)) {
                System.out.println("[i] Deleted " + CONFIG_FILE
                        + " - the next run will ask for the server address again.");
            } else {
                System.out.println("[i] No " + CONFIG_FILE + " to delete.");
            }
        } catch (IOException e) {
            System.err.println("[!] Could not delete " + CONFIG_FILE + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Asking the user
    // ------------------------------------------------------------------

    /** Prompts for the server IP/hostname. Returns null if the user cancels. */
    private static String askHost() {
        while (true) {
            String answer = ask("Enter the server IP address or hostname: ");
            if (answer == null) {
                return null;
            }
            answer = answer.trim();
            if (!answer.isEmpty()) {
                return answer;
            }
            System.err.println("  -> The address cannot be empty.");
        }
    }

    /** Prompts for the port, re-asking until it is a valid port or Ctrl+C. */
    private static int askPort() {
        while (true) {
            String answer = ask("Enter the server port [" + DEFAULT_PORT + "]: ");
            if (answer == null) {
                return -1;
            }
            answer = answer.trim();
            if (answer.isEmpty()) {
                return DEFAULT_PORT;
            }
            int port = parsePort(answer);
            if (port >= 0) {
                return port;
            }
            System.err.println("  -> Please enter a number between "
                    + MIN_PORT + " and " + MAX_PORT + ".");
        }
    }

    /**
     * Reads one line from the user. Uses the real console when there is one so
     * it works as a proper interactive prompt, and falls back to stdin when
     * the program is started from an IDE or with redirected input.
     *
     * NOTE: the fallback reader is created once and reused. Creating a new
     * BufferedReader per prompt would be a bug: the first read buffers ALL
     * available input, so a second prompt would find the stream already at
     * EOF and any piped input after the first answer would be silently lost.
     */
    private static String ask(String message) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("%s", message);
        }
        System.out.print(message);
        System.out.flush();
        try {
            if (stdin == null) {
                stdin = new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8));
            }
            String answer = stdin.readLine();
            if (answer == null) {
                System.err.println();
                System.err.println("Error: no input available (stdin is at end of file).");
                System.err.println("Type the address interactively, or pass it as an argument:");
                System.err.println("  java LiveTimeClient <host> [port]");
            }
            return answer;
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses and range-checks a port. Returns -1 (and explains) if invalid. */
    private static int parsePort(String text) {
        int port;
        try {
            port = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be a number, got \"" + text + "\".");
            System.err.println("Allowed range is " + MIN_PORT + "-" + MAX_PORT + ".");
            return -1;
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            System.err.println("Error: port " + port + " is out of range. Allowed range is "
                    + MIN_PORT + "-" + MAX_PORT + ".");
            return -1;
        }
        return port;
    }
}
