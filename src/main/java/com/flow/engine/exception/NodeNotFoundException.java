package com.flow.engine.exception;

public class NodeNotFoundException extends FlowException {

    public NodeNotFoundException(String flowId, String nodeId) {
        super("Node not found: '" + nodeId + "' in flow '" + flowId + "'", flowId, nodeId);
    }
}
