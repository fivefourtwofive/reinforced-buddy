package tech.amak.portbuddy.common.dto.threatfox;

public record ThreatFoxRequest(
    String query,
    Integer days
) {}
