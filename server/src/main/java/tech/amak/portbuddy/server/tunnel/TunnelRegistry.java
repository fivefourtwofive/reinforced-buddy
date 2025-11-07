package tech.amak.portbuddy.server.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tech.amak.portbuddy.common.tunnel.HttpTunnelMessage;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

/** Registry of active HTTP tunnels and WS connections. */
@Slf4j
@Component
public class TunnelRegistry {

  private final Map<String, Tunnel> bySubdomain = new ConcurrentHashMap<>();
  private final Map<String, Tunnel> byTunnelId = new ConcurrentHashMap<>();
  private final ObjectMapper mapper = new ObjectMapper();

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  public Tunnel createPending(String subdomain, String tunnelId) {
    final var t = new Tunnel(subdomain, tunnelId);
    bySubdomain.put(subdomain, t);
    byTunnelId.put(tunnelId, t);
    return t;
  }

  public void remove(Tunnel t) {
    bySubdomain.remove(t.subdomain());
    byTunnelId.remove(t.tunnelId());
  }

  public Tunnel getBySubdomain(String subdomain) {
    return bySubdomain.get(subdomain);
  }

  public Tunnel getByTunnelId(String tunnelId) {
    return byTunnelId.get(tunnelId);
  }

  public boolean attachSession(String tunnelId, WebSocketSession session) {
    final var t = byTunnelId.get(tunnelId);
    if (t == null) return false;
    t.setSession(session);
    return true;
  }

  public CompletableFuture<HttpTunnelMessage> forwardRequest(String subdomain, HttpTunnelMessage req, Duration timeout) {
    final var t = bySubdomain.get(subdomain);
    if (t == null || !t.isOpen()) {
      final var cf = new CompletableFuture<HttpTunnelMessage>();
      cf.completeExceptionally(new IllegalStateException("Tunnel not connected"));
      return cf;
    }
    // Assign id if missing
    if (req.getId() == null) {
      req.setId(UUID.randomUUID().toString());
    }
    req.setType(HttpTunnelMessage.Type.REQUEST);
    final var fut = new CompletableFuture<HttpTunnelMessage>();
    t.pending().put(req.getId(), fut);
    try {
      final var json = mapper.writeValueAsString(req);
      t.session().sendMessage(new TextMessage(json));
    } catch (IOException e) {
      t.pending().remove(req.getId());
      fut.completeExceptionally(e);
      return fut;
    }
    if (timeout == null) timeout = DEFAULT_TIMEOUT;
    final var to = timeout;
    // Apply timeout
    return fut.orTimeout(to.toMillis(), TimeUnit.MILLISECONDS).whenComplete((res, err) -> {
      t.pending().remove(req.getId());
    });
  }

  public void onResponse(String tunnelId, HttpTunnelMessage resp) {
    final var t = byTunnelId.get(tunnelId);
    if (t == null) return;
    final var fut = t.pending().get(resp.getId());
    if (fut != null) {
      fut.complete(resp);
    }
  }

  // ============ WebSocket tunneling support ============
  public void sendWsToClient(String tunnelId, WsTunnelMessage m) {
    final var t = byTunnelId.get(tunnelId);
    if (t == null || !t.isOpen()) return;
    try {
      final var json = mapper.writeValueAsString(m);
      t.session().sendMessage(new TextMessage(json));
    } catch (IOException e) {
      log.warn("Failed to send WS message to client: {}", e.toString());
    }
  }

  public void registerBrowserWs(String tunnelId, String connectionId, WebSocketSession browserSession) {
    final var t = byTunnelId.get(tunnelId);
    if (t == null) return;
    t.browserByConn().put(connectionId, browserSession);
    t.browserReverse().put(browserSession, new Ids(tunnelId, connectionId));
  }

  public Ids unregisterBrowserWs(WebSocketSession browserSession) {
    for (var t : byTunnelId.values()) {
      final var ids = t.browserReverse().remove(browserSession);
      if (ids != null) {
        t.browserByConn().remove(ids.connectionId);
        return ids;
      }
    }
    return null;
  }

  public Ids findIdsByBrowserSession(WebSocketSession browserSession) {
    for (var t : byTunnelId.values()) {
      final var ids = t.browserReverse().get(browserSession);
      if (ids != null) return ids;
    }
    return null;
  }

  public WebSocketSession getBrowserSession(String tunnelId, String connectionId) {
    final var t = byTunnelId.get(tunnelId);
    if (t == null) return null;
    return t.browserByConn().get(connectionId);
  }

  @Data
  @AllArgsConstructor
  public static class Ids {
    private String tunnelId;
    private String connectionId;
  }

  public static class Tunnel {
    private final String subdomain;
    private final String tunnelId;
    private volatile WebSocketSession session;
    private final Map<String, CompletableFuture<HttpTunnelMessage>> pending = new ConcurrentHashMap<>();
    // Browser WS peers for this tunnel
    private final Map<String, WebSocketSession> browserByConn = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Ids> browserReverse = new ConcurrentHashMap<>();

    public Tunnel(String subdomain, String tunnelId) {
      this.subdomain = subdomain;
      this.tunnelId = tunnelId;
    }

    public String subdomain() { return subdomain; }
    public String tunnelId() { return tunnelId; }
    public WebSocketSession session() { return session; }
    public void setSession(WebSocketSession s) { this.session = s; }
    public Map<String, CompletableFuture<HttpTunnelMessage>> pending() { return pending; }
    public boolean isOpen() { return session != null && session.isOpen(); }

    public Map<String, WebSocketSession> browserByConn() { return browserByConn; }
    public Map<WebSocketSession, Ids> browserReverse() { return browserReverse; }
  }
}
