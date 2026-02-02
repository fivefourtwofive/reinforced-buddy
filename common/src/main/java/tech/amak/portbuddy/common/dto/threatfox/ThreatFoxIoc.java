package tech.amak.portbuddy.common.dto.threatfox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreatFoxIoc(
    @JsonProperty("id") String id,
    @JsonProperty("ioc") String ioc,
    @JsonProperty("threat_type") String threattype,
    @JsonProperty("ioc_type") String ioctype,
    @JsonProperty("malware") String malware,
    @JsonProperty("malware_printable") String malwareprintable,
    @JsonProperty("confidence_level") Integer confidencelevel,
    @JsonProperty("first_seen") String firstseen,
    @JsonProperty("reporter") String reporter
) {}
