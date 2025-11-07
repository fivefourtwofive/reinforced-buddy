package tech.amak.portbuddy.server.tunnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tech.amak.portbuddy.common.tunnel.HttpTunnelMessage;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

@Slf4j
@Component
@RequiredArgsConstructor
public class TunnelWebSocketHandler extends TextWebSocketHandler {

  private final TunnelRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    if (tunnelId == null || !registry.attachSession(tunnelId, session)) {
      log.warn("Tunnel not found for id={}", tunnelId);
      session.close(CloseStatus.NORMAL);
      return;
    }
    log.info("Tunnel session established: {}", tunnelId);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    final String payload = message.getPayload();
    final JsonNode node = mapper.readTree(payload);
    if (node.has("kind") && "WS".equals(node.get("kind").asText())) {
      final var wsMsg = mapper.treeToValue(node, WsTunnelMessage.class);
      handleWsFromClient(tunnelId, wsMsg);
      return;
    }
    final var httpMsg = mapper.treeToValue(node, HttpTunnelMessage.class);
    if (httpMsg.getType() == HttpTunnelMessage.Type.RESPONSE) {
      registry.onResponse(tunnelId, httpMsg);
    } else {
      log.debug("Ignoring unexpected message type from client: {}", httpMsg.getType());
    }
  }

  private void handleWsFromClient(String tunnelId, WsTunnelMessage m) throws Exception {
    final var browser = registry.getBrowserSession(tunnelId, m.getConnectionId());
    if (browser == null) {
      log.debug("No browser WS for connectionId={} tunnelId={}", m.getConnectionId(), tunnelId);
      return;
    }
    switch (m.getWsType()) {
      case OPEN_OK -> { /* nothing extra for now */ }
      case TEXT -> browser.sendMessage(new TextMessage(m.getText() != null ? m.getText() : ""));
      case BINARY -> {
        if (m.getDataB64() != null) {
          final var bytes = Base64.getDecoder().decode(m.getDataB64());
          browser.sendMessage(new org.springframework.web.socket.BinaryMessage(bytes));
        }
      }
      case CLOSE -> {
        final var code = m.getCloseCode() != null ? m.getCloseCode() : CloseStatus.NORMAL.getCode();
        final var reason = m.getCloseReason();
        browser.close(new CloseStatus(code, reason));
      }
      default -> {}
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    final var t = registry.getByTunnelId(tunnelId);
    if (t != null) {
      t.setSession(null);
      log.info("Tunnel session closed: {}", tunnelId);
    }
  }

  private String extractTunnelId(URI uri) {
    if (uri == null) return null;
    final var path = uri.getPath();
    if (path == null) return null;
    final var parts = path.split("/");
    return parts.length > 0 ? parts[parts.length - 1] : null;
  }
}
