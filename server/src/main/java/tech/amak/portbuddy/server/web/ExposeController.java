package tech.amak.portbuddy.server.web;

import java.security.SecureRandom;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.amak.portbuddy.server.web.dto.HttpExposeRequest;
import tech.amak.portbuddy.server.web.dto.ExposeResponse;

@RestController
@RequestMapping(path = "/api/expose", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExposeController {

  private final SecureRandom random = new SecureRandom();

  @PostMapping("/http")
  public ExposeResponse exposeHttp(@RequestBody HttpExposeRequest req) {
    final var subdomain = randomSubdomain();
    final var publicUrl = "https://" + subdomain + ".port-buddy.com";
    final var source = "http://" + req.host() + ":" + req.port();
    return new ExposeResponse(source, publicUrl, null, null);
  }

  @PostMapping("/tcp")
  public ExposeResponse exposeTcp(@RequestBody HttpExposeRequest req) {
    final var proxyIdx = 1 + random.nextInt(10);
    final var publicHost = "tcp-proxy-" + proxyIdx + ".port-buddy.com";
    final var publicPort = 30000 + random.nextInt(20000);
    final var source = "tcp " + req.host() + ":" + req.port();
    return new ExposeResponse(source, null, publicHost, publicPort);
  }

  private String randomSubdomain() {
    final var animals = new String[] {"falcon", "lynx", "orca", "otter", "swift", "sparrow", "tiger", "puma"};
    final var name = animals[random.nextInt(animals.length)];
    final var num = 1000 + random.nextInt(9000);
    return name + "-" + num;
  }
}
