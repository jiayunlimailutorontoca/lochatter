package org.webrtc.audio;

/**
 * Off until the call's media path is up and the on-device model has loaded.
 * The record thread reads this before it copies a frame.
 */
public final class CaptionGate {
  public static volatile boolean enabled;

  private CaptionGate() {}
}
