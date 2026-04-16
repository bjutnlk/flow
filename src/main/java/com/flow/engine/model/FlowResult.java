package com.flow.engine.model;

import java.util.List;
import java.util.Map;

/**
 * Captures the outcome of a complete flow execution, including execution
 * trace, final context snapshot, success/failure status, and a reference
 * to the persistent execution log.
 */
public class FlowResult {

    private final String flowId;
    private final boolean success;
    private final Map<String, Object> variables;
    private final List<String> executionTrace;
    private final String errorMessage;
    private final String executionId;

    private FlowResult(String flowId, boolean success, Map<String, Object> variables,
                       List<String> executionTrace, String errorMessage, String executionId) {
        this.flowId = flowId;
        this.success = success;
        this.variables = variables;
        this.executionTrace = executionTrace;
        this.errorMessage = errorMessage;
        this.executionId = executionId;
    }

    public static FlowResult success(String flowId, Map<String, Object> variables,
                                     List<String> executionTrace, String executionId) {
        return new FlowResult(flowId, true, variables, executionTrace, null, executionId);
    }

    public static FlowResult failure(String flowId, Map<String, Object> variables,
                                     List<String> executionTrace, String errorMessage,
                                     String executionId) {
        return new FlowResult(flowId, false, variables, executionTrace, errorMessage, executionId);
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

    /**
     * The unique execution log ID.  Use this to query the
     * {@link com.flow.engine.recorder.ExecutionRecorder} for
     * detailed per-node execution records.
     */
    public String getExecutionId() {
        return executionId;
    }

    @Override
    public String toString() {
        return "FlowResult{flowId='" + flowId + "', success=" + success
                + ", executionId='" + executionId + "'"
                + ", trace=" + executionTrace
                + (errorMessage != null ? ", error='" + errorMessage + "'" : "")
                + '}';
    }
}
