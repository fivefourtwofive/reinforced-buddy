package tech.amak.portbuddy.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import tech.amak.portbuddy.common.tunnel.HttpTunnelMessage;
import tech.amak.portbuddy.common.tunnel.WsTunnelMessage;

@Slf4j
@RequiredArgsConstructor
public class HttpTunnelClient {

  private final String serverUrl; // e.g. https://api.port-buddy.com
  private final String tunnelId;
  private final String localHost;
  private final int localPort;

  private final OkHttpClient http = new OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS) // keep-alive for WS
      .build();
  private final ObjectMapper mapper = new ObjectMapper();

  private WebSocket ws;
  private final CountDownLatch closeLatch = new CountDownLatch(1);

  private final Map<String, WebSocket> localWsByConn = new ConcurrentHashMap<>();

  public void runBlocking() {
    final var wsUrl = toWebSocketUrl(serverUrl, "/api/tunnel/" + tunnelId);
    final var req = new Request.Builder().url(wsUrl).build();
    ws = http.newWebSocket(req, new Listener());

    try {
      closeLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String toWebSocketUrl(String base, String path) {
    var u = URI.create(base);
    var scheme = u.getScheme();
    if ("https".equalsIgnoreCase(scheme)) {
      scheme = "wss";
    } else if ("http".equalsIgnoreCase(scheme)) {
      scheme = "ws";
    }
    final var hostPort = (u.getPort() == -1) ? u.getHost() : (u.getHost() + ":" + u.getPort());
    return scheme + "://" + hostPort + path;
  }

  private class Listener extends WebSocketListener {
    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      log.info("Tunnel connected to server");
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      try {
        final JsonNode node = mapper.readTree(text);
        if (node.has("kind") && "WS".equals(node.get("kind").asText())) {
          final var wsMsg = mapper.treeToValue(node, WsTunnelMessage.class);
          handleWsFromServer(wsMsg);
          return;
        }
        final var msg = mapper.treeToValue(node, HttpTunnelMessage.class);
        if (msg.getType() == HttpTunnelMessage.Type.REQUEST) {
          final var resp = handleRequest(msg);
          final var json = mapper.writeValueAsString(resp);
          webSocket.send(json);
        } else {
          log.debug("Ignoring non-REQUEST msg");
        }
      } catch (Exception e) {
        log.warn("Failed to process WS message: {}", e.toString());
      }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      log.info("Tunnel closed: {} {}", code, reason);
      closeLatch.countDown();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
      log.warn("Tunnel failure: {}", t.toString());
      closeLatch.countDown();
    }
  }

  private void handleWsFromServer(WsTunnelMessage m) {
    final var connId = m.getConnectionId();
    switch (m.getWsType()) {
      case OPEN -> {
        // Connect to local target via WS
        var url = "ws://" + localHost + ":" + localPort + (m.getPath() != null ? m.getPath() : "/");
        if (m.getQuery() != null && !m.getQuery().isBlank()) {
          url += "?" + m.getQuery();
        }
        final var rb = new Request.Builder().url(url);
        if (m.getHeaders() != null) {
          for (var e : m.getHeaders().entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
              rb.addHeader(e.getKey(), e.getValue());
            }
          }
        }
        final var local = http.newWebSocket(rb.build(), new LocalWsListener(connId));
        localWsByConn.put(connId, local);
      }
      case TEXT -> {
        final var local = localWsByConn.get(connId);
        if (local != null) local.send(m.getText() != null ? m.getText() : "");
      }
      case BINARY -> {
        final var local = localWsByConn.get(connId);
        if (local != null && m.getDataB64() != null) {
          local.send(ByteString.of(Base64.getDecoder().decode(m.getDataB64())));
        }
      }
      case CLOSE -> {
        final var local = localWsByConn.remove(connId);
        if (local != null) local.close(m.getCloseCode() != null ? m.getCloseCode() : 1000, m.getCloseReason());
      }
      default -> {}
    }
  }

  private class LocalWsListener extends WebSocketListener {
    private final String connectionId;
    LocalWsListener(String connectionId) { this.connectionId = connectionId; }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      try {
        final var ack = new WsTunnelMessage();
        ack.setWsType(WsTunnelMessage.Type.OPEN_OK);
        ack.setConnectionId(connectionId);
        ws.send(mapper.writeValueAsString(ack));
      } catch (Exception ignore) {}
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      try {
        final var m = new WsTunnelMessage();
        m.setWsType(WsTunnelMessage.Type.TEXT);
        m.setConnectionId(connectionId);
        m.setText(text);
        ws.send(mapper.writeValueAsString(m));
      } catch (Exception e) {
        log.debug("Failed to forward local text WS: {}", e.toString());
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      try {
        final var m = new WsTunnelMessage();
        m.setWsType(WsTunnelMessage.Type.BINARY);
        m.setConnectionId(connectionId);
        m.setDataB64(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        ws.send(mapper.writeValueAsString(m));
      } catch (Exception e) {
        log.debug("Failed to forward local binary WS: {}", e.toString());
      }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      try {
        localWsByConn.remove(connectionId);
        final var m = new WsTunnelMessage();
        m.setWsType(WsTunnelMessage.Type.CLOSE);
        m.setConnectionId(connectionId);
        m.setCloseCode(code);
        m.setCloseReason(reason);
        ws.send(mapper.writeValueAsString(m));
      } catch (Exception e) {
        log.debug("Failed to notify close: {}", e.toString());
      }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
      onClosed(webSocket, 1011, t.toString());
    }
  }

  private HttpTunnelMessage handleRequest(HttpTunnelMessage reqMsg) {
    final var method = reqMsg.getMethod();
    var url = "http://" + localHost + ":" + localPort + reqMsg.getPath();
    if (reqMsg.getQuery() != null && !reqMsg.getQuery().isBlank()) {
      url += "?" + reqMsg.getQuery();
    }

    final var rb = new Request.Builder().url(url).method(method, buildBody(method, reqMsg.getBodyB64()));

    if (reqMsg.getHeaders() != null) {
      for (var e : reqMsg.getHeaders().entrySet()) {
        final var name = e.getKey();
        final var value = e.getValue();
        if (name == null || value == null) continue;
        if (name.equalsIgnoreCase("Host")) continue; // Host will be set by client
        rb.addHeader(name, value);
      }
    }

    try (var resp = http.newCall(rb.build()).execute()) {
      final var respMsg = new HttpTunnelMessage();
      respMsg.setId(reqMsg.getId());
      respMsg.setType(HttpTunnelMessage.Type.RESPONSE);
      respMsg.setStatus(resp.code());
      respMsg.setRespHeaders(extractHeaders(resp));
      final var body = resp.body();
      if (body != null) {
        final var bytes = body.bytes();
        if (bytes.length > 0) {
          respMsg.setRespBodyB64(Base64.getEncoder().encodeToString(bytes));
        }
      }
      return respMsg;
    } catch (IOException e) {
      final var err = new HttpTunnelMessage();
      err.setId(reqMsg.getId());
      err.setType(HttpTunnelMessage.Type.RESPONSE);
      err.setStatus(502);
      final var headers = new HashMap<String, String>();
      headers.put("Content-Type", "text/plain; charset=utf-8");
      err.setRespHeaders(headers);
      err.setRespBodyB64(Base64.getEncoder().encodeToString(("Bad Gateway: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
      return err;
    }
  }

  private RequestBody buildBody(String method, String bodyB64) {
    // Methods that usually don't have body
    if (bodyB64 == null) return methodSupportsBody(method) ? RequestBody.create(new byte[0], null) : null;
    final var bytes = Base64.getDecoder().decode(bodyB64);
    return RequestBody.create(bytes, MediaType.parse("application/octet-stream"));
  }

  private boolean methodSupportsBody(String method) {
    if (method == null) return false;
    return switch (method.toUpperCase()) {
      case "POST", "PUT", "PATCH" -> true;
      default -> false;
    };
  }

  private Map<String, String> extractHeaders(Response resp) {
    final var map = new HashMap<String, String>();
    for (var name : resp.headers().names()) {
      map.put(name, resp.header(name));
    }
    return map;
  }
}
