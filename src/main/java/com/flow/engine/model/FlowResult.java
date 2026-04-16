package com.flow.engine.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Captures the outcome of a complete flow execution, including execution
 * trace, final context snapshot, and success/failure status.
 */
public class FlowResult {

    private final String flowId;
    private final boolean success;
    private final Map<String, Object> variables;
    private final List<String> executionTrace;
    private final String errorMessage;

    private FlowResult(String flowId, boolean success, Map<String, Object> variables,
                       List<String> executionTrace, String errorMessage) {
        this.flowId = flowId;
        this.success = success;
        this.variables = variables;
        this.executionTrace = executionTrace;
        this.errorMessage = errorMessage;
    }

    public static FlowResult success(String flowId, Map<String, Object> variables,
                                     List<String> executionTrace) {
        return new FlowResult(flowId, true, variables, executionTrace, null);
    }

    public static FlowResult failure(String flowId, Map<String, Object> variables,
                                     List<String> executionTrace, String errorMessage) {
        return new FlowResult(flowId, false, variables, executionTrace, errorMessage);
    }

    public String getFlowId() {
        return flowId;
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public List<String> getExecutionTrace() {
        return executionTrace;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "FlowResult{flowId='" + flowId + "', success=" + success
                + ", trace=" + executionTrace
                + (errorMessage != null ? ", error='" + errorMessage + "'" : "")
                + '}';
    }
}
