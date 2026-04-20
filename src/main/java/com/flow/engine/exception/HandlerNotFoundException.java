package com.flow.engine.exception;

public class HandlerNotFoundException extends FlowException {

    public HandlerNotFoundException(String flowId, String nodeType) {
        super("No handler registered for node type: '" + nodeType + "' in flow '" + flowId + "'",
                flowId, null);
    }
}
