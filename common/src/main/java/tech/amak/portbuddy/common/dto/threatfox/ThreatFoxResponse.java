package tech.amak.portbuddy.common.dto.threatfox;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreatFoxResponse(
    @JsonProperty("query_status") String querystatus,
    @JsonProperty("data") List<ThreatFoxIoc> data
) {}
