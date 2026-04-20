package com.flow.engine.recorder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Records the complete execution of a flow run — one instance per
 * {@code FlowEngine.execute()} call.
 *
 * <p>Contains summary-level information plus an ordered list of
 * {@link NodeExecutionLog} entries for every node that was executed.
 *
 * <p>When persisting to a database, this maps to a row in a
 * {@code flow_execution_log} table, with a one-to-many relationship
 * to {@code flow_node_execution_log}.
 */
public class ExecutionLog {

    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED
    }

    private final String executionId;
    private final String flowId;
    private final String flowName;
    private final String flowVersion;
    private Status status;
    private Instant startTime;
    private Instant endTime;
    private long totalDurationMs;
    private int totalNodes;
    private int successNodes;
    private int failedNodes;
    private String errorMessage;
    private final List<NodeExecutionLog> nodeExecutionLogs = new ArrayList<>();

    public ExecutionLog(String flowId, String flowName, String flowVersion) {
        this.executionId = UUID.randomUUID().toString();
        this.flowId = flowId;
        this.flowName = flowName;
        this.flowVersion = flowVersion;
        this.status = Status.RUNNING;
        this.startTime = Instant.now();
    }

    public void addNodeLog(NodeExecutionLog nodeLog) {
        nodeExecutionLogs.add(nodeLog);
    }

    public void markSuccess() {
        this.status = Status.SUCCESS;
        this.endTime = Instant.now();
        this.totalDurationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        summarize();
    }

    public void markFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.endTime = Instant.now();
        this.totalDurationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        this.errorMessage = errorMessage;
        summarize();
    }

    private void summarize() {
        this.totalNodes = nodeExecutionLogs.size();
        this.successNodes = (int) nodeExecutionLogs.stream()
                .filter(n -> n.getStatus() == NodeExecutionLog.Status.SUCCESS)
                .count();
        this.failedNodes = (int) nodeExecutionLogs.stream()
                .filter(n -> n.getStatus() == NodeExecutionLog.Status.FAILED)
                .count();
    }

    // ---- getters ----

    public String getExecutionId() {
        return executionId;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getFlowName() {
        return flowName;
    }

    public String getFlowVersion() {
        return flowVersion;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public int getTotalNodes() {
        return totalNodes;
    }

    public int getSuccessNodes() {
        return successNodes;
    }

    public int getFailedNodes() {
        return failedNodes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<NodeExecutionLog> getNodeExecutionLogs() {
        return Collections.unmodifiableList(nodeExecutionLogs);
    }

    @Override
    public String toString() {
        return "ExecutionLog{id='" + executionId + "', flow='" + flowId
                + "', status=" + status
                + ", nodes=" + totalNodes + "(ok=" + successNodes + ",fail=" + failedNodes + ")"
                + ", duration=" + totalDurationMs + "ms"
                + (errorMessage != null ? ", error='" + errorMessage + "'" : "")
                + '}';
    }
}
