package tech.amak.portbuddy.netproxy.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.amak.portbuddy.common.dto.threatfox.ThreatFoxIoc;
import tech.amak.portbuddy.common.dto.threatfox.ThreatFoxRequest;
import tech.amak.portbuddy.common.dto.threatfox.ThreatFoxResponse;
import tech.amak.portbuddy.netproxy.config.ThreatFoxProperties;
import tech.amak.portbuddy.netproxy.security.ThreatBlockedException;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ThreatFoxProperties.class)
public class ThreatFoxService {

    private static final String IOC_TYPE_IP_PORT = "ip:port";
    private static final String IOC_TYPE_DOMAIN = "domain";

    private final ThreatFoxProperties properties;
    private final RestTemplate resttemplate = new RestTemplate();
    private final Map<String, Boolean> ioccache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        fetchdata();
    }

    @Scheduled(fixedRateString = "${threatfox.fetchintervalminutes:60}000", initialDelay = 3600000)
    public void schedulefetch() {
        fetchdata();
    }

    public void fetchdata() {
        try {
            final var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Auth-Key", properties.authkey());

            final var request = new ThreatFoxRequest("get_iocs", 1);
            final var entity = new HttpEntity<>(request, headers);

            final var response = resttemplate.postForObject(
                properties.apiurl(),
                entity,
                ThreatFoxResponse.class
            );

            processresponse(response);
        } catch (final Exception e) {
            log.error("threatfox fetch failed: {}", e.getMessage());
        }
    }

    private void processresponse(final ThreatFoxResponse response) {
        if (response == null || response.data() == null) {
            log.warn("threatfox returned empty response");
            return;
        }

        ioccache.clear();

        int count = 0;
        for (final ThreatFoxIoc ioc : response.data()) {
            if (isrelevanttype(ioc.ioctype())) {
                final var normalized = normalizeioc(ioc.ioc());
                ioccache.put(normalized, Boolean.TRUE);
                count++;
            }
        }

        log.info("threatfox cache updated: {} iocs loaded", count);
    }

    private boolean isrelevanttype(final String ioctype) {
        return IOC_TYPE_IP_PORT.equalsIgnoreCase(ioctype) || IOC_TYPE_DOMAIN.equalsIgnoreCase(ioctype);
    }

    private String normalizeioc(final String ioc) {
        if (ioc == null) {
            return "";
        }
        return ioc.toLowerCase().trim();
    }

    public boolean isblacklisted(final String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        return ioccache.containsKey(target.toLowerCase().trim());
    }

    public void checkthreat(final String host, final int port) {
        final var ipport = host + ":" + port;

        if (isblacklisted(host)) {
            log.warn("threat blocked: domain {} matches ioc", host);
            throw new ThreatBlockedException("target host is blacklisted: " + host);
        }

        if (isblacklisted(ipport)) {
            log.warn("threat blocked: {} matches ioc", ipport);
            throw new ThreatBlockedException("target is blacklisted: " + ipport);
        }
    }

    public int getcachesize() {
        return ioccache.size();
    }
}
