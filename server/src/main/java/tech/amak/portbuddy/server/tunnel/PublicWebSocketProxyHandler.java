package tech.amak.portbuddy.server.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

/**
 * Accepts public WebSocket connections from browsers for tunneled subdomains and bridges them
 * over the control WebSocket to the CLI client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicWebSocketProxyHandler extends AbstractWebSocketHandler {

  private final TunnelRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void afterConnectionEstablished(final WebSocketSession browserSession) throws Exception {
    final var subdomain = extractSubdomain(browserSession);
    if (subdomain == null) {
      log.debug("WS: missing/invalid host header");
      browserSession.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    final var tunnel = registry.getBySubdomain(subdomain);
    if (tunnel == null || !tunnel.isOpen()) {
      browserSession.close(CloseStatus.SERVICE_RESTARTED);
      return;
    }
    final var connId = UUID.randomUUID().toString();
    registry.registerBrowserWs(tunnel.tunnelId(), connId, browserSession);

    final var uri = browserSession.getUri();
    final var msg = new WsTunnelMessage();
    msg.setConnectionId(connId);
    msg.setWsType(WsTunnelMessage.Type.OPEN);
    if (uri != null) {
      msg.setPath(uri.getPath());
      msg.setQuery(uri.getQuery());
    }
    // Forward Sec-WebSocket-Protocol if requested
    final var proto = browserSession.getHandshakeHeaders().getFirst("Sec-WebSocket-Protocol");
    if (proto != null) {
      msg.setHeaders(Map.of("Sec-WebSocket-Protocol", proto));
    }

    registry.sendWsToClient(tunnel.tunnelId(), msg);
  }

  @Override
  protected void handleTextMessage(final WebSocketSession session, final TextMessage message) throws Exception {
    final var ids = registry.findIdsByBrowserSession(session);
    if (ids == null) return;
    final var m = new WsTunnelMessage();
    m.setConnectionId(ids.getConnectionId());
    m.setWsType(WsTunnelMessage.Type.TEXT);
    m.setText(message.getPayload());
    registry.sendWsToClient(ids.getTunnelId(), m);
  }

  @Override
  protected void handleBinaryMessage(final WebSocketSession session, final BinaryMessage message) throws Exception {
    final var ids = registry.findIdsByBrowserSession(session);
    if (ids == null) return;
    final var m = new WsTunnelMessage();
    m.setConnectionId(ids.getConnectionId());
    m.setWsType(WsTunnelMessage.Type.BINARY);
    m.setDataB64(Base64.getEncoder().encodeToString(message.getPayload().array()));
    registry.sendWsToClient(ids.getTunnelId(), m);
  }

  @Override
  public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) throws Exception {
    final var ids = registry.unregisterBrowserWs(session);
    if (ids == null) return;
    final var m = new WsTunnelMessage();
    m.setConnectionId(ids.getConnectionId());
    m.setWsType(WsTunnelMessage.Type.CLOSE);
    m.setCloseCode(status.getCode());
    m.setCloseReason(status.getReason());
    registry.sendWsToClient(ids.getTunnelId(), m);
  }

  private String extractSubdomain(final WebSocketSession session) {
    final var host = session.getHandshakeHeaders().getFirst(HttpHeaders.HOST);
    if (host == null) return null;
    if (!host.endsWith(".port-buddy.com")) return null;
    final var idx = host.indexOf('.');
    if (idx <= 0) return null;
    return host.substring(0, idx);
  }
}
