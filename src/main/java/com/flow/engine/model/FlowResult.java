package com.flow.engine.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Captures the outcome of a complete flow execution.
 *
 * <p>{@code returnValues} contains the values declared in the end node's
 * {@code properties.returnValues} — this is the flow's "return value".
 */
public class FlowResult {

    private final String flowId;
    private final boolean success;
    private final Map<String, Object> variables;
    private final Map<String, Object> returnValues;
    private final List<String> executionTrace;
    private final String errorMessage;
    private final String executionId;

    private FlowResult(String flowId, boolean success, Map<String, Object> variables,
                       Map<String, Object> returnValues,
                       List<String> executionTrace, String errorMessage, String executionId) {
        this.flowId = flowId;
        this.success = success;
        this.variables = variables;
        this.returnValues = returnValues != null ? returnValues : Collections.emptyMap();
        this.executionTrace = executionTrace;
        this.errorMessage = errorMessage;
        this.executionId = executionId;
    }

    public static FlowResult success(String flowId, Map<String, Object> variables,
                                     Map<String, Object> returnValues,
                                     List<String> executionTrace, String executionId) {
        return new FlowResult(flowId, true, variables, returnValues, executionTrace, null, executionId);
    }

    public static FlowResult failure(String flowId, Map<String, Object> variables,
                                     List<String> executionTrace, String errorMessage,
                                     String executionId) {
        return new FlowResult(flowId, false, variables, null, executionTrace, errorMessage, executionId);
    }

    public String getFlowId() { return flowId; }
    public boolean isSuccess() { return success; }
    public Map<String, Object> getVariables() { return variables; }
    public List<String> getExecutionTrace() { return executionTrace; }
    public String getErrorMessage() { return errorMessage; }
    public String getExecutionId() { return executionId; }

    /**
     * The flow's return values, collected from the end node's
     * {@code properties.returnValues} declarations.
     */
    public Map<String, Object> getReturnValues() { return returnValues; }

    @Override
    public String toString() {
        return "FlowResult{flowId='" + flowId + "', success=" + success
                + ", executionId='" + executionId + "'"
                + ", returnValues=" + returnValues
                + ", trace=" + executionTrace
                + (errorMessage != null ? ", error='" + errorMessage + "'" : "")
                + '}';
    }
}
