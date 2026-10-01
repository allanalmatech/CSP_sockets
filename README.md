# TCP Time Server (Java 17) — Client/Server Programming for Applications

A TCP server that returns the local machine's system time to any connecting
client, plus the matching client and a UDP (bonus) version.

```
TimeServer.java     TCP server, iterative, port 6000-65535 (default 6000)
TimeClient.java     TCP client:  java TimeClient <host> [port]
LiveTimeServer.java TCP server that keeps PUSHING live time (streaming)
LiveTimeClient.java Streaming client; remembers the server in config.txt
UdpTimeServer.java  UDP server (bonus)
UdpTimeClient.java  UDP client (bonus)
```

---

## 1. C / WinSock → Java mapping

| C / WinSock call | Java equivalent | Notes |
|---|---|---|
| `WSAStartup()` / `WSACleanup()` | **Hidden** | The JVM loads and initialises the native socket library when it starts. Nothing to call, nothing to clean up. |
| `socket(AF_INET, SOCK_STREAM, 0)` | `new ServerSocket()` / `new Socket()` | `SOCK_STREAM` is implied. |
| `struct sockaddr_in` + `memset` | `new InetSocketAddress(port)` | A typed object instead of a raw struct + cast. |
| `bind(s, (sockaddr*)&addr, len)` | `serverSocket.bind(new InetSocketAddress(port))` | |
| `listen(s, backlog)` | **Implicit** | No `listen()` method exists. Pass the backlog to the constructor: `new ServerSocket(port, backlog)`. |
| `accept(s, ...)` | `Socket s2 = serverSocket.accept()` | Returns a *new* connected socket. |
| `connect(s, ...)` | `new Socket(host, port)` or `socket.connect(new InetSocketAddress(host, port))` | |
| `send(s, buf, len, 0)` | `socket.getOutputStream().write(...)` | Wrapped in a `Writer` so text is handled for you. |
| `recv(s, buf, len, 0)` | `socket.getInputStream().read(...)` | Wrapped in a `Reader`. |
| `closesocket(s)` | **try-with-resources** | `close()` is called automatically. |
| `htons()` / `htonl()` | **Hidden** | Done inside the JDK when the address is written to the socket. |
| `inet_addr("1.2.3.4")` | `InetAddress.getByName("1.2.3.4")` | |
| `gethostbyname(host)` | `InetAddress.getByName(host)` | Also does the reverse (`getHostAddress()`). Throws `UnknownHostException`. |
| `setsockopt(SO_REUSEADDR)` | `setReuseAddress(true)` | |
| `recvfrom()` / `sendto()` | `DatagramSocket.receive(pkt)` / `.send(pkt)` | UDP only. |
| `FD_SET` / `select()` | `java.nio.channels.Selector` | Not needed here — a single blocking `accept()` loop suffices. |

**Summary of what Java hides and why:** the Java API is a *safe, object-oriented
abstraction* over the OS socket API. The JVM already calls `WSAStartup` for you,
and because you never touch raw bytes in a `sockaddr_in` struct, the JVM performs
the `htons()`/`htonl()` byte-order swap for you. Java also guarantees sockets are
closed via try-with-resources, removing a whole class of C bugs.

---

## 2. Compile and run

> The folder path contains spaces, so **quote paths** in Windows PowerShell/cmd.

### Compile (once)

```powershell
cd "F:\BSE IV\Client Server Programming for Applications\project\sockets"
javac TimeServer.java TimeClient.java UdpTimeServer.java UdpTimeClient.java LiveTimeServer.java LiveTimeClient.java
```

The six `.class` files land next to the sources, so no `-cp` is needed.

### Terminal 1 — the server

```powershell
java TimeServer          # listens on 6000 (default)
java TimeServer 6789     # or choose your own port in 6000-65535
```

Expected output:

```
TimeServer listening on port 6000 (bound to 0.0.0.0)
Press Ctrl+C to stop.
```

### Terminal 2 — the client (same machine, localhost)

```powershell
java TimeClient localhost        # default port 6000
java TimeClient 127.0.0.1 6000   # explicit
```

Expected output:

```
[+] Connected to 127.0.0.1:6000  (from local port 55934)
Server time: 2026-10-01 21:24:06 EAT
```

The server terminal now shows the client that connected:

```
[+] Client connected: 127.0.0.1:55934  (local port 6000)
    -> Sent: "2026-10-01 21:24:06 EAT"
```

### Terminal 2 — the client (second machine, same network)

1. On the **server** machine find its LAN IP:
   - Windows: `ipconfig` → look for *IPv4 Address*, e.g. `192.168.56.1`
   - Linux/macOS: `ip addr` / `ifconfig`
2. On the **client** machine run:

```powershell
java TimeClient 192.168.56.1 6000
```

Both machines must be on the same subnet and the **port must be allowed through
the firewall** (see section 3). If Windows Firewall prompts, choose
*Allow access* for **Private networks**.

### UDP bonus

```powershell
java UdpTimeServer 6000      # terminal 1
java UdpTimeClient localhost 6000   # terminal 2
```

The TCP and UDP servers can use **the same port number 6000 at the same time** —
TCP and UDP have separate port namespaces. This is a good demo point.

---

## 2b. The "live time" version (streams instead of one-shot)

`LiveTimeServer` / `LiveTimeClient` do the same job, but instead of sending the
time once and hanging up, the server **keeps pushing** a fresh timestamp every
interval for as long as the client stays connected. The client redraws the same
terminal line, so the clock visibly ticks.

### Two things change vs. the basic version

**1. The server must be CONCURRENT, not iterative.** `TimeServer` serves one
client to completion and loops back to `accept()`. `LiveTimeServer` would get
stuck forever inside the send-loop of the first client and could never accept a
second one — so it gives **each client its own thread**. The
`LiveTimeClient` below demonstrates this: two clients can watch at once.

**2. The server must detect disconnects.** A write to a socket the client has
already closed can succeed silently, so the server never learns the client left
and keeps pushing into a dead connection. The fix is a short read timeout: the
client never sends anything, so any read is purely a disconnect probe —
`read()` returning `-1` (EOF) means the client closed, and a
`SocketTimeoutException` just means "still there, carry on".

### Run it

```powershell
# Terminal 1 - the server (port, then update interval in ms)
java LiveTimeServer                 # 6000, 1000 ms
java LiveTimeServer 6000 500        # twice a second

# Terminal 2 - the client
java LiveTimeClient                 # asks for the server, then remembers it
java LiveTimeClient 192.168.56.1    # skip the prompt, and remember this
java LiveTimeClient 192.168.56.1 6000
java LiveTimeClient --forget        # wipe config.txt, ask again next time
```

Server output:

```
LiveTimeServer streaming on port 6000 (bound to 0.0.0.0)
Update interval: 1000 ms
Press Ctrl+C to stop.
[+] Client connected: 192.168.56.1:56615
[-] Client disconnected: 192.168.56.1:56615
```

Client output (the `\r` redraws one line, so it ticks in place):

```
[i] No server address saved yet in config.txt.
Enter the server IP address or hostname: 192.168.56.1
Enter the server port [6000]:
[i] Saved to config.txt - next time I will not ask again.
[+] Connected to 192.168.56.1:6000 - streaming time, press Ctrl+C to stop.

Server time: 2026-10-01 21:49:09 EAT
```

### config.txt — the "memory"

On first run the client asks for the server address and saves it **next to the
program** in `config.txt`:

```properties
#Remembered LiveTimeServer address. Edit or delete this file to change it.
#Thu Oct 01 21:49:05 EAT 2026
port=6000
host=192.168.56.1
```

On every later run it prints `[i] Using remembered server ... from config.txt`
and connects without asking anything — which is the point: you can copy the
folder to another PC, type the server IP once, and it works from then on.

- It is a plain `key=value` file, so you can also **edit it by hand** to point at
  a different machine.
- `java LiveTimeClient --forget` deletes it when you want to be asked afresh.
- The prompt uses the real console when there is one, and falls back to piped
  stdin when launched from an IDE or with redirected input.
- Passing a host on the command line **also** writes it to `config.txt`, so an
  override becomes the new remembered default.

### Watch two clients at once

This is the practical proof that the live server is concurrent. Start the
server, then run two clients in two terminals — both tick at once. The basic
`TimeServer` cannot do this.

---

## 3. Verifying it works

### Check the port is listening

```powershell
netstat -ano | findstr :6000
```

With a client connected, you see **two** server sockets — a direct, visual
demonstration of the two-socket model. This is the actual output captured while
one client was still connected (PID 48804 is the server):

```
  TCP    0.0.0.0:6000        0.0.0.0:0        LISTENING     48804   <- listening socket
  TCP    127.0.0.1:6000      127.0.0.1:56329  FIN_WAIT_2    48804   <- socket from accept()
  TCP    127.0.0.1:56329     127.0.0.1:6000   CLOSE_WAIT    50276   <- the client side
  TCP    [::]:6000           [::]:0           LISTENING     48804   <- IPv6 listener
```

Read it like this: the listening socket keeps the **fixed** port 6000 and is
bound to **0.0.0.0**, while the socket produced by `accept()` has a **random
ephemeral** port (56329) and is bound to one specific client. Both have the
**same PID**, which proves they are two distinct sockets belonging to one
process.

### Connect without writing a client — netcat

```powershell
ncat localhost 6000          # Netcat for Windows
nc localhost 6000            # Linux/macOS
```

### Connect without writing a client — telnet

```powershell
telnet localhost 6000
```

You immediately see the time line, then the server logs the connection and the
telnet session ends (the server closed its end). This proves the server works
with *any* TCP client, not just yours.

> **Note:** `telnet` is an *optional feature* on modern Windows and is often not
> installed. If `telnet` is "not recognised", either enable it with
> *Settings → Apps → Optional features → Telnet Client*, or use this
> PowerShell one-liner instead — it is a genuine raw TCP client, needs no extra
> software, and is verified to print the time:
>
> ```powershell
> $c = New-Object System.Net.Sockets.TcpClient "localhost",6000
> (New-Object System.IO.StreamReader($c.GetStream())).ReadLine()
> ```
>
> Output: `2026-10-01 21:34:52 EAT`

### Demonstrate the error handling

| Command | Result |
|---|---|
| `java TimeServer 80` | `Error: port 80 is out of range. Allowed range is 6000-65535.` |
| `java TimeServer abc` | `Error: port must be a number, got "abc".` |
| `java TimeServer 6000` (twice) | `Error: cannot bind to the requested port - Address already in use: bind` |
| `java TimeClient localhost 6199` | `Error: connection refused by localhost:6199 - is the server running?` |
| `java TimeClient no-such-host.invalid` | `Error: cannot resolve host "no-such-host.invalid" ...` |
| `java UdpTimeClient localhost 6200` (no server) | `Error: no reply from localhost:6200 within 5000 ms.` |
| `java LiveTimeServer 6000 10` | `Error: interval must be between 100 and 60000 ms, got 10.` |
| `java LiveTimeClient localhost 6100` (no server) | `Error: connection refused by localhost:6100 - is LiveTimeServer running...?` |
| `java LiveTimeClient --forget` | Deletes `config.txt` so the next run asks again. |

### Wireshark — see the TCP handshake

1. Start Wireshark, choose the interface that carries the traffic (loopback for
   localhost tests), start capture.
2. Display filter: `tcp.port == 6000`
3. Run the client.
4. In the packet list you will see the three-way handshake, then the server's
   data, then the teardown:

```
1  0.000  192.168.56.1  →  192.168.56.1   SYN              (client → server)
2  0.003  192.168.56.1  ←  192.168.56.1   SYN, ACK         (server → client)
3  0.004  192.168.56.1  →  192.168.56.1   ACK              (client → server)   ← connection established
4  0.008  192.168.56.1  ←  192.168.56.1   PSH, ACK  Len=27 (server → client)  ← the time string
5  0.011  192.168.56.1  →  192.168.56.1   FIN, ACK         (server → client)   ← server closes
```

Packets 1-3 are the SYN / SYN-ACK / ACK handshake. This is the practical
proof of "why TCP" — three packets are spent establishing the connection before
a single byte of the time string is sent.

For the UDP run, filter `udp.port == 6000` and you will see exactly two
datagrams — no handshake, no teardown.

### Two-machine test checklist

- [ ] Same subnet / both machines can `ping` each other
- [ ] Server bound to `0.0.0.0` (not `127.0.0.1`) so it answers on the LAN
- [ ] Inbound TCP 6000 allowed on the server's firewall
- [ ] Client uses the server's LAN IP, **not** `localhost`

---

## 4. Presentation script

### Why TCP?

TCP is *connection-oriented* and *reliable*: it guarantees the bytes arrive
exactly once, in the correct order, with error detection and retransmission of
anything lost. For this assignment that matters because the time string is a
single message that must arrive intact — a dropped or reordered byte would give
the client garbage. The connection also gives immediate feedback: if no server
is listening, `connect()` fails instantly with "connection refused" rather than
the client hanging silently. The cost is overhead: the three-way handshake
before any data, plus header space on every packet, plus a connection to tear
down. That overhead is why the UDP bonus version is interesting to compare.

### What the server does, step by step

1. **Parse and validate the port** from `args[0]`, defaulting to 6000 and
   rejecting anything outside 6000–65535.
2. **Create a socket** (`new ServerSocket()`) — an unbound TCP socket.
3. **Bind** it to port 6000 on `INADDR_ANY` (`0.0.0.0`), so it accepts clients
   on every network interface of the machine.
4. **Listen** — implied by the bind; the OS now queues incoming connections.
5. **Loop forever:**
   a. **`accept()`** — blocks until a client connects, then returns a new,
   already-connected socket.
   b. **Log** the client's IP address and ephemeral port.
   c. **Read the clock** with `ZonedDateTime.now(ZoneId.systemDefault())` —
   the *server's* time, as the assignment requires — and format it.
   d. **`send()`** the text line over the accepted socket.
   e. **`close()`** that one client socket, then go back to step 5a.

This is an **iterative** server: one client is served to completion at a time,
with no threads.

### The listening socket vs. the socket returned by `accept()`

This is the distinction students most often get wrong.

- The **listening socket** (`ServerSocket`) is created once and stays open for
  the entire life of the program. It is bound to a *fixed, well-known* port
  (6000) that clients use to find the server. It **never sends or receives
  application data** — its only job is to answer the phone.
- The socket returned by **`accept()`** is a **brand-new socket** created for one
  specific client. It is *already connected* to that client, it gets a **random
  ephemeral port** on the server side, and *this* is the socket you call
  `send()`/`getOutputStream()` on. When that client is done, you close *this*
  socket — never the listening one, or the server would stop accepting.

One connection = one accepted socket, but the listening socket is shared by all
of them. `netstat` shows both sockets at once, which proves they are separate.

### Why no network byte order conversion when sending text

`htons()`/`htonl()` exist because `struct sockaddr_in` is a **packed binary
header** holding a 16-bit port and a 32-bit IP address. TCP/IP requires those
fields to be transmitted in big-endian order, but x86 CPUs store integers
little-endian, so C requires an explicit swap — get it wrong and the server
binds to a garbled port.

In Java this problem **disappears twice over**:

1. `new InetSocketAddress(port)` is a typed object, not a raw struct. The JDK
   performs the byte-order conversion internally when it writes the address to
   the socket, so there is no `htons()` to call.
2. The **payload itself is text**. Byte order only matters when you encode
   multi-byte *numbers* into a header. A time string like `2026-10-01 14:32:05 EAT`
   is just ASCII/UTF-8 characters, and TCP is a byte stream that preserves
   order — so there is no reordering to do at all. I send the string with
   `Writer.write()` and Java turns it into UTF-8 bytes in the correct order.

(If the payload *were* numbers, `DataOutputStream.writeInt()` / `readInt()` do
the same conversion automatically — still no manual `htons()`.)

---

## 5. Three likely lecturer questions

**Q1. Your server is iterative. What happens if two clients connect at the
same time, and how would you fix it?**

The `accept()` loop handles one client at a time, so the second client waits
until the first has been served. Its TCP connection is not refused — the OS
completes the handshake and parks it in the accept queue (the *backlog*, 50 by
default here), so the client sees a successful connection and just waits for its
reply. The fix is a **concurrent** server: handle each accepted socket in its own
thread. `LiveTimeServer` in this project is exactly that — `accept()` stays in
the main loop and each client socket is handed to a new daemon thread, so two
clients can watch the live clock simultaneously. For larger loads you would
replace the raw threads with an `ExecutorService` thread pool to cap the number
of threads. UDP is unaffected — it has no connection, so nothing queues.

A related trap with long-lived connections: writing to a socket the client has
already closed can *succeed*, so a streaming server never learns the client left.
`LiveTimeServer` therefore uses a short `setSoTimeout` and treats any read as a
disconnect probe — `read()` returning `-1` means the client closed, while a
`SocketTimeoutException` just means "still connected".

**Q2. What is the difference between the listening socket and the socket
`accept()` returns, and why do you bind to `0.0.0.0`?**

They are two different sockets. The listening socket is bound to the fixed
well-known port 6000, never carries application data, and lives for the whole
program; `accept()` returns a separate, already-connected socket with a random
ephemeral port, used to `send()`/`recv()` for exactly one client and then closed.
`0.0.0.0` is `INADDR_ANY`: it means "accept connections arriving on **any** of
this machine's network interfaces", so the server is reachable from the LAN. If
I bound to `127.0.0.1` instead, only that same machine could ever connect.

**Q3. Why doesn't your Java code call `htons()` or `htonl()` like the C slides do?**

Because Java never exposes the raw `struct sockaddr_in` bytes to the programmer.
`htons()`/`htonl()` exist purely to fix the big-endian/little-endian mismatch
between CPU memory and the TCP/IP wire format when you pack a port or IP into
that struct. In Java you build a typed `InetSocketAddress` and the JDK performs
the conversion internally, so the bug class simply cannot occur. And for this
assignment the payload is human-readable text, where byte order is irrelevant —
TCP is a byte stream and preserves ordering. Byte order only ever mattered for
binary *number* headers, and even then `DataOutputStream`/`DataInputStream`
would handle it automatically.

---

## Appendix — verified test run

All paths below were executed and confirmed on JDK 17.0.12:

- Compiles cleanly with `javac -Xlint:all` (zero warnings)
- Client over `localhost`, `127.0.0.1` and `::1` (IPv6)
- Client over the machine's LAN IP `192.168.56.1`
- Default port (omitted) and explicit port
- Four sequential clients, each logged with a distinct ephemeral source port
- Server survives a client that connects and disconnects abruptly
- All error paths in the table in section 3
- TCP and UDP servers bound to port 6000 simultaneously
- Live server streaming at 500 ms and 1000 ms intervals
- Live server detecting both graceful and abrupt client disconnects
- **Two live clients streaming simultaneously** (proves the threaded server)
- `config.txt` written on first run, then recalled on run #2 with no prompt
- `--forget` deleting the config, and the CLI override being remembered

### Two Java gotchas worth mentioning in the presentation

Both of these were real bugs found while testing, and both are excellent
"what did you learn" points:

1. **`new DatagramSocket()` is already bound.** The no-argument constructor
   silently binds a random ephemeral port, so a later `bind()` throws
   `SocketException: Already bound`. To create an *unbound* UDP socket you must
   pass `null`: `new DatagramSocket((InetSocketAddress) null)`. The TCP
   equivalent (`new ServerSocket()`) genuinely is unbound, which makes the
   inconsistency easy to trip over.
2. **Never wrap `System.in` in a fresh `BufferedReader` per prompt.** The first
   read buffers *all* available input, so a second prompt finds the stream at
   EOF and any piped input is silently lost. `LiveTimeClient` creates the reader
   once and reuses it.
