package com.flow.engine.exception;

/**
 * Thrown when a flow definition's version is too old or unrecognized.
 */
public class UnsupportedVersionException extends FlowException {

    private final String version;
    private final String minVersion;

    public UnsupportedVersionException(String flowId, String version, String minVersion) {
        super("Flow '" + flowId + "' version '" + version
                        + "' is not supported (minimum required: " + minVersion + ")",
                flowId, null);
        this.version = version;
        this.minVersion = minVersion;
    }

    public String getVersion() {
        return version;
    }

    public String getMinVersion() {
        return minVersion;
    }
}
