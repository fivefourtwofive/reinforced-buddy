package tech.amak.portbuddy.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "threatfox")
public record ThreatFoxProperties(
    String apiurl,
    String authkey,
    Integer fetchintervalminutes
) {
    public ThreatFoxProperties {
        if (apiurl == null || apiurl.isBlank()) {
            apiurl = "https://threatfox-api.abuse.ch/api/v1/";
        }
        if (authkey == null || authkey.isBlank()) {
            authkey = "сюда authkey, но лучше не ставь его сюда опенсорс все же ;)";
        }
        if (fetchintervalminutes == null || fetchintervalminutes <= 0) {
            fetchintervalminutes = 60;
        }
    }
}
