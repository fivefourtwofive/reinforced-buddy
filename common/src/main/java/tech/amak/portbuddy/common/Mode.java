package tech.amak.portbuddy.common;

/** Supported expose modes. */
public enum Mode {
  HTTP,
  TCP;

  public static Mode from(String mode) {
    if (mode == null) {
      return HTTP;
    }
      return switch (mode.toLowerCase()) {
          case "http" -> HTTP;
          case "tcp" -> TCP;
          default -> throw new IllegalArgumentException("Unknown mode: " + mode);
      };
  }
}
