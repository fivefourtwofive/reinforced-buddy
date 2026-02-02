package tech.amak.portbuddy.netproxy.security;

public class ThreatBlockedException extends SecurityException {

    public ThreatBlockedException(final String message) {
        super(message);
    }
}
