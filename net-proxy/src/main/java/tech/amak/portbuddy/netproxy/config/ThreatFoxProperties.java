package tech.amak.portbuddy.netproxy.config;

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
            authkey = "YOUR-AUTH-KEY";
        }
        if (fetchintervalminutes == null || fetchintervalminutes <= 0) {
            fetchintervalminutes = 60;
        }
    }
}
