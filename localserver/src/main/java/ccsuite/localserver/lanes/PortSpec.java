package ccsuite.localserver.lanes;

/** One port to serve: its name (pclaude, fclaude), the loopback port and the lane it serves. */
public record PortSpec(String name, int port, LaneKind kind) {}
