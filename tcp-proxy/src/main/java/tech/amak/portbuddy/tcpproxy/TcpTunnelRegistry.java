package tech.amak.portbuddy.tcpproxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

@Slf4j
@Component
public class TcpTunnelRegistry {

    private final Map<String, Tunnel> byTunnelId = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    public ExposedPort expose(final String tunnelId) throws IOException {
        final var tunnel = byTunnelId.computeIfAbsent(tunnelId, Tunnel::new);
        if (tunnel.serverSocket != null && !tunnel.serverSocket.isClosed()) {
            return new ExposedPort(tunnel.serverSocket.getLocalPort());
        }
        final var serverSocket = new ServerSocket(0);
        tunnel.serverSocket = serverSocket;
        ioPool.execute(() -> acceptLoop(tunnel));
        return new ExposedPort(serverSocket.getLocalPort());
    }

    public void attachSession(final String tunnelId, final WebSocketSession session) {
        final var tunnel = byTunnelId.computeIfAbsent(tunnelId, Tunnel::new);
        tunnel.session = session;
    }

    public void detachSession(final WebSocketSession session) {
        for (final var tunnel : byTunnelId.values()) {
            if (tunnel.session == session) {
                tunnel.session = null;
                break;
            }
        }
    }

    private void acceptLoop(final Tunnel tunnel) {
        try {
            while (!tunnel.serverSocket.isClosed()) {
                final var socket = tunnel.serverSocket.accept();
                final var connId = UUID.randomUUID().toString();
                final var connection = new Connection(connId, socket);
                tunnel.connections.put(connId, connection);
                sendOpen(tunnel, connId);
                ioPool.execute(() -> pumpFromPublic(tunnel, connection));
            }
        } catch (IOException e) {
            log.info("Accept loop ended for tunnel {}: {}", tunnel.tunnelId, e.toString());
        }
    }

    private void pumpFromPublic(final Tunnel tunnel, final Connection connection) {
        final var buf = new byte[8192];
        try {
            while (true) {
                final var next = connection.in.read(buf);
                if (next == -1) {
                    break;
                }
                final var message = new WsTunnelMessage();
                message.setWsType(WsTunnelMessage.Type.BINARY);
                message.setConnectionId(connection.connectionId);
                message.setDataB64(Base64.getEncoder().encodeToString(Arrays.copyOf(buf, next)));
                sendToClient(tunnel, message);
            }
        } catch (IOException ignore) {
        } finally {
            try {
                connection.socket.close();
            } catch (IOException ignore) {
            }
            tunnel.connections.remove(connection.connectionId);
            final var m = new WsTunnelMessage();
            m.setWsType(WsTunnelMessage.Type.CLOSE);
            m.setConnectionId(connection.connectionId);
            sendToClient(tunnel, m);
        }
    }

    public void onClientBinary(final String tunnelId, final String connectionId, final String dataB64) {
        final var tunnel = byTunnelId.get(tunnelId);
        if (tunnel == null) {
            return;
        }
        final var connection = tunnel.connections.get(connectionId);
        if (connection == null) {
            return;
        }
        try {
            connection.out.write(Base64.getDecoder().decode(dataB64));
            connection.out.flush();
        } catch (IOException e) {
            log.debug("Failed to write to public socket: {}", e.toString());
        }
    }

    public void onClientClose(final String tunnelId, final String connectionId) {
        final var tunnel = byTunnelId.get(tunnelId);
        if (tunnel == null) {
            return;
        }
        final var connection = tunnel.connections.remove(connectionId);
        if (connection != null) {
            try {
                connection.socket.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void sendOpen(final Tunnel tunnel, final String connId) {
        final var message = new WsTunnelMessage();
        message.setWsType(WsTunnelMessage.Type.OPEN);
        message.setConnectionId(connId);
        sendToClient(tunnel, message);
    }

    private void sendToClient(final Tunnel tunnel, final WsTunnelMessage m) {
        try {
            if (tunnel.session != null && tunnel.session.isOpen()) {
                tunnel.session.sendMessage(new TextMessage(mapper.writeValueAsString(m)));
            }
        } catch (IOException e) {
            log.debug("Failed to send to client: {}", e.toString());
        }
    }

    @Data
    public static class ExposedPort {
        private final int port;
    }

    @Data
    private static class Tunnel {
        private final String tunnelId;
        private volatile WebSocketSession session;
        private volatile ServerSocket serverSocket;
        private final Map<String, Connection> connections = new ConcurrentHashMap<>();

        Tunnel(final String tunnelId) {
            this.tunnelId = tunnelId;
        }
    }

    private static class Connection {
        final String connectionId;
        final Socket socket;
        final java.io.InputStream in;
        final java.io.OutputStream out;

        Connection(final String connectionId, final Socket socket) throws IOException {
            this.connectionId = connectionId;
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }
    }
}
