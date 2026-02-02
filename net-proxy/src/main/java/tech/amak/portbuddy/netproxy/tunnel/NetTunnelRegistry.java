/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.amak.portbuddy.netproxy.tunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.amak.portbuddy.common.TunnelType;
import tech.amak.portbuddy.common.tunnel.BinaryWsFrame;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

@Slf4j
@Component
@RequiredArgsConstructor
public class NetTunnelRegistry {

    private final Map<UUID, Tunnel> byTunnelId = new ConcurrentHashMap<>();
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    private final ObjectMapper mapper;
    private final tech.amak.portbuddy.netproxy.service.ThreatFoxService threatfoxservice;

    public ExposedPort expose(final UUID tunnelId, final TunnelType tunnelType, final Integer desiredPort)
        throws IOException {
        return switch (tunnelType) {
            case UDP -> exposeudp(tunnelId, desiredPort);
            case TCP -> exposetcp(tunnelId, desiredPort);
            default -> throw new IllegalArgumentException("unsupported tunnel type: " + tunnelType);
        };
    }

    private ExposedPort exposetcp(final UUID tunnelId, final Integer desiredPort) throws IOException {
        final var tunnel = byTunnelId.computeIfAbsent(tunnelId, Tunnel::new);
        if (tunnel.serverSocket != null && !tunnel.serverSocket.isClosed()) {
            return new ExposedPort(tunnel.serverSocket.getLocalPort());
        }
        final ServerSocket serverSocket;
        if (desiredPort != null && desiredPort > 0) {
            ServerSocket sock;
            try {
                sock = new ServerSocket(desiredPort);
            } catch (final IOException bindEx) {
                // Requested port is busy; fallback to a random available port
                log.info("TCP port {} is busy. Falling back to a random port.", desiredPort);
                sock = new ServerSocket(0);
            }
            serverSocket = sock;
        } else {
            serverSocket = new ServerSocket(0);
        }
        tunnel.serverSocket = serverSocket;
        ioPool.execute(() -> acceptloop(tunnel));
        return new ExposedPort(serverSocket.getLocalPort());
    }

    private ExposedPort exposeudp(final UUID tunnelId, final Integer desiredPort) throws IOException {
        final var tunnel = byTunnelId.computeIfAbsent(tunnelId, Tunnel::new);
        if (tunnel.udpSocket != null && !tunnel.udpSocket.isClosed()) {
            return new ExposedPort(tunnel.udpSocket.getLocalPort());
        }
        final DatagramSocket socket;
        if (desiredPort != null && desiredPort > 0) {
            DatagramSocket sock;
            try {
                sock = new DatagramSocket(desiredPort);
            } catch (final IOException bindEx) {
                // Requested port is busy; fallback to a random available port
                log.info("UDP port {} is busy. Falling back to a random port.", desiredPort);
                sock = new DatagramSocket(0);
            }
            socket = sock;
        } else {
            socket = new DatagramSocket(0);
        }
        tunnel.udpSocket = socket;
        ioPool.execute(() -> udpreceiveloop(tunnel));
        return new ExposedPort(socket.getLocalPort());
    }

    public void attachsession(final UUID tunnelId, final WebSocketSession session) {
        final var tunnel = byTunnelId.computeIfAbsent(tunnelId, Tunnel::new);
        tunnel.session = session;
    }

    public void detachsession(final WebSocketSession session) {
        for (final var tunnel : byTunnelId.values()) {
            if (tunnel.session == session) {
                tunnel.session = null;
                break;
            }
        }
    }

    public void closetunnel(final UUID tunnelId) {
        final var tunnel = byTunnelId.remove(tunnelId);
        if (tunnel == null) {
            return;
        }
        // Close TCP acceptor first so accept loops break
        final var server = tunnel.serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (final Exception e) {
                log.debug("Failed to close ServerSocket: {}", e.toString());
            }
        }
        // Close all live TCP connections
        for (final var entry : tunnel.connections.entrySet()) {
            final var connection = entry.getValue();
            try {
                connection.socket.close();
            } catch (final Exception e) {
                log.debug("Failed to close connection {}: {}", entry.getKey(), e.toString());
            }
        }
        tunnel.connections.clear();
        // Close UDP socket
        final var udp = tunnel.udpSocket;
        if (udp != null) {
            try {
                udp.close();
            } catch (final Exception e) {
                log.debug("Failed to close DatagramSocket: {}", e.toString());
            }
        }
        tunnel.udpRemotes.clear();
        tunnel.session = null;
    }

    private void acceptloop(final Tunnel tunnel) {
        try {
            while (!tunnel.serverSocket.isClosed()) {
                final var socket = tunnel.serverSocket.accept();
                final var remotehost = socket.getInetAddress().getHostAddress();
                final var remoteport = socket.getPort();
                try {
                    threatfoxservice.checkthreat(remotehost, remoteport);
                } catch (final tech.amak.portbuddy.netproxy.security.ThreatBlockedException e) {
                    log.warn("blocked threat connection from {}:{}", remotehost, remoteport);
                    socket.close();
                    continue;
                }
                final var connId = UUID.randomUUID().toString();
                final var connection = new Connection(connId, socket);
                tunnel.connections.put(connId, connection);
                sendopen(tunnel, connId);
            }
        } catch (final Exception e) {
            log.info("accept loop ended for tunnel {}: {}", tunnel.tunnelId, e.toString());
        }
    }

    private void pumpfrompublic(final Tunnel tunnel, final Connection connection) {
        final var buffer = new byte[8192];
        try {
            while (true) {
                final var next = connection.in.read(buffer);
                if (next == -1) {
                    break;
                }
                sendbinarytoclient(tunnel, connection.connectionId, buffer, 0, next);
            }
        } catch (final Exception ignore) {
            log.error("Failed to read from public socket: {}", ignore.toString());
        } finally {
            log.info("Public socket closed for tunnel {}: {}", tunnel.tunnelId, connection.connectionId);
            try {
                connection.socket.close();
            } catch (final Exception ignore) {
                log.error("Failed to close public socket: {}", ignore.toString());
            }
            tunnel.connections.remove(connection.connectionId);
            final var message = new WsTunnelMessage();
            message.setWsType(WsTunnelMessage.Type.CLOSE);
            message.setConnectionId(connection.connectionId);
            sendtoclient(tunnel, message);
        }
    }

    private void udpreceiveloop(final Tunnel tunnel) {
        final var buffer = new byte[65535];
        try {
            while (tunnel.udpSocket != null && !tunnel.udpSocket.isClosed()) {
                final var packet = new DatagramPacket(buffer, buffer.length);
                tunnel.udpSocket.receive(packet);
                final var remotehost = packet.getAddress().getHostAddress();
                final var remoteport = packet.getPort();
                try {
                    threatfoxservice.checkthreat(remotehost, remoteport);
                } catch (final tech.amak.portbuddy.netproxy.security.ThreatBlockedException e) {
                    log.warn("blocked threat udp packet from {}:{}", remotehost, remoteport);
                    continue;
                }
                final var remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
                final var connectionId = remote.getHostString() + ":" + remote.getPort();
                tunnel.udpRemotes.putIfAbsent(connectionId, remote);
                sendbinarytoclient(tunnel, connectionId, packet.getData(), packet.getOffset(), packet.getLength());
            }
        } catch (final Exception e) {
            log.info("udp receive loop ended for tunnel {}: {}", tunnel.tunnelId, e.toString());
        }
    }

    public void onclientopenok(final UUID tunnelId, final String connectionId) {
        final var tunnel = byTunnelId.get(tunnelId);
        if (tunnel == null) {
            return;
        }
        final var connection = tunnel.connections.get(connectionId);
        if (connection == null) {
            return;
        }
        ioPool.execute(() -> pumpfrompublic(tunnel, connection));
    }

    public void onclientbinary(final UUID tunnelId, final String connectionId, final String dataB64) {
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
        } catch (final IOException e) {
            log.debug("failed to write to public socket: {}", e.toString());
        }
    }

    public void onclientbinarybytes(final UUID tunnelId, final String connectionId, final byte[] data) {
        final var tunnel = byTunnelId.get(tunnelId);
        if (tunnel == null) {
            return;
        }
        // If UDP is active on this tunnel, route as a datagram
        if (tunnel.udpSocket != null) {
            final var remote = tunnel.udpRemotes.get(connectionId);
            if (remote == null) {
                return;
            }
            try {
                final var packet = new DatagramPacket(data, 0, data.length, remote);
                tunnel.udpSocket.send(packet);
            } catch (final IOException e) {
                log.debug("Failed to send UDP packet: {}", e.toString());
            }
            return;
        }

        // Else assume TCP
        final var connection = tunnel.connections.get(connectionId);
        if (connection == null) {
            return;
        }
        try {
            connection.out.write(data);
            connection.out.flush();
        } catch (final IOException e) {
            log.debug("Failed to write to public socket: {}", e.toString());
        }
    }

    public void onclientclose(final UUID tunnelId, final String connectionId) {
        final var tunnel = byTunnelId.get(tunnelId);
        if (tunnel == null) {
            return;
        }
        if (tunnel.udpSocket != null) {
            // Just remove mapping; no need to close the UDP socket itself
            tunnel.udpRemotes.remove(connectionId);
        } else {
            final var connection = tunnel.connections.remove(connectionId);
            if (connection != null) {
                try {
                    connection.socket.close();
                } catch (final IOException ignore) {
                    log.error("Failed to close public socket: {}", ignore.toString());
                }
            }
        }
    }

    private void sendopen(final Tunnel tunnel, final String connId) {
        final var message = new WsTunnelMessage();
        message.setWsType(WsTunnelMessage.Type.OPEN);
        message.setConnectionId(connId);
        sendtoclient(tunnel, message);
    }

    private void sendtoclient(final Tunnel tunnel, final WsTunnelMessage message) {
        try {
            if (tunnel.session != null && tunnel.session.isOpen()) {
                tunnel.session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
            }
        } catch (final IOException e) {
            log.debug("failed to send to client: {}", e.toString());
        }
    }

    private void sendbinarytoclient(final Tunnel tunnel,
                                    final String connectionId,
                                    final byte[] bytes,
                                    final int offset,
                                    final int length) {
        try {
            if (tunnel.session != null && tunnel.session.isOpen()) {
                final var payload = BinaryWsFrame.encodeToByteBuffer(connectionId, bytes, offset, length);
                tunnel.session.sendMessage(new BinaryMessage(payload));
            }
        } catch (final IOException e) {
            log.debug("failed to send binary to client: {}", e.toString());
        }
    }

    @Data
    public static class ExposedPort {
        private final int port;
    }

    @Data
    private static class Tunnel {
        private final UUID tunnelId;
        private volatile WebSocketSession session;
        private volatile ServerSocket serverSocket;
        private final Map<String, Connection> connections = new ConcurrentHashMap<>();
        private volatile DatagramSocket udpSocket;
        private final Map<String, InetSocketAddress> udpRemotes = new ConcurrentHashMap<>();

        Tunnel(final UUID tunnelId) {
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
