package com.flow.engine.recorder;

import java.time.Instant;
import java.util.Map;

/**
 * Records the execution detail of a single node within a flow run.
 *
 * <p>When persisting to a database, this maps to a row in a
 * {@code flow_node_execution_log} table.
 */
public class NodeExecutionLog {

    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private final String nodeId;
    private final String nodeType;
    private final String nodeName;
    private final int stepIndex;
    private Status status;
    private Instant startTime;
    private Instant endTime;
    private long durationMs;
    private String errorMessage;
    private Map<String, Object> inputSnapshot;
    private Map<String, Object> outputSnapshot;

    public NodeExecutionLog(String nodeId, String nodeType, String nodeName, int stepIndex) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.nodeName = nodeName;
        this.stepIndex = stepIndex;
        this.status = Status.RUNNING;
        this.startTime = Instant.now();
    }

    public void markSuccess(Map<String, Object> outputSnapshot) {
        this.status = Status.SUCCESS;
        this.endTime = Instant.now();
        this.durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        this.outputSnapshot = outputSnapshot;
    }

    public void markFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.endTime = Instant.now();
        this.durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
        this.errorMessage = errorMessage;
    }

    public void markSkipped() {
        this.status = Status.SKIPPED;
        this.endTime = this.startTime;
        this.durationMs = 0;
    }

    // ---- getters ----

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getNodeName() {
        return nodeName;
    }

    public int getStepIndex() {
        return stepIndex;
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

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getInputSnapshot() {
        return inputSnapshot;
    }

    public void setInputSnapshot(Map<String, Object> inputSnapshot) {
        this.inputSnapshot = inputSnapshot;
    }

    public Map<String, Object> getOutputSnapshot() {
        return outputSnapshot;
    }

    @Override
    public String toString() {
        return "NodeLog{#" + stepIndex + " " + nodeId + "(" + nodeType + ") " + status
                + " " + durationMs + "ms"
                + (errorMessage != null ? " error='" + errorMessage + "'" : "")
                + '}';
    }
}
