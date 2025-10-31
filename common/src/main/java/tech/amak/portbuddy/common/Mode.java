package tech.amak.portbuddy.common;

/** Supported expose modes. */
public enum Mode {
  HTTP,
  TCP;

  public static Mode from(String s) {
    if (s == null) {
      return HTTP;
    }
    switch (s.toLowerCase()) {
      case "http":
        return HTTP;
      case "tcp":
        return TCP;
      default:
        throw new IllegalArgumentException("Unknown mode: " + s);
    }
  }
}
