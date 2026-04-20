package com.flow.engine.exception;

/**
 * Base exception for all flow-engine errors.
 */
public class FlowException extends RuntimeException {

    private final String flowId;
    private final String nodeId;

    public FlowException(String message, String flowId, String nodeId) {
        super(message);
        this.flowId = flowId;
        this.nodeId = nodeId;
    }

    public FlowException(String message, String flowId, String nodeId, Throwable cause) {
        super(message, cause);
        this.flowId = flowId;
        this.nodeId = nodeId;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
