package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * A single executable node within a flow definition.
 *
 * <h3>Retry support</h3>
 * <p>When retrying a failed flow, each node in the JSON may carry
 * {@code status} and {@code outputData} from the previous run:
 * <ul>
 *   <li>{@code status: "SUCCESS"} + {@code outputData: {...}} — node
 *       already succeeded; engine skips re-execution and restores output</li>
 *   <li>{@code status: "FAILED"} — node failed; engine re-executes it</li>
 *   <li>no status — node not yet reached; engine executes normally</li>
 * </ul>
 */
public class FlowNode {

    private String id;
    private String type;
    private String name;

    private List<String> prevNodes;
    private List<String> next;

    @JsonIgnore
    private List<String> waitFor;

    private Map<String, Object> properties = new HashMap<>();
    private List<Branch> branches;
    private List<InputMapping> inputMappings;
    private List<OutputMapping> outputMappings;
    private String body;

    /**
     * Execution status from previous run (for retry).
     * Null on first execution; "SUCCESS", "FAILED", etc. on retry.
     */
    private String status;

    /**
     * Output data from previous successful execution (for retry).
     * When {@code status == "SUCCESS"}, the engine restores this
     * into the context instead of re-executing the handler.
     */
    private Map<String, Object> outputData;

    /**
     * Error message from previous failed execution (for retry).
     */
    private String errorMessage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // ---- prevNodes ----

    public List<String> getPrevNodes() { return prevNodes; }
    public void setPrevNodes(List<String> prevNodes) { this.prevNodes = prevNodes; }

    // ---- next ----

    public List<String> getNext() { return next; }

    @JsonSetter("next")
    public void setNextFromJson(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            this.next = null;
        } else if (jsonNode.isArray()) {
            this.next = new ArrayList<>();
            jsonNode.forEach(n -> this.next.add(n.asText()));
        } else {
            this.next = new ArrayList<>(List.of(jsonNode.asText()));
        }
    }

    public void setNext(List<String> next) { this.next = next; }

    public String getFirstNext() {
        return next != null && !next.isEmpty() ? next.get(0) : null;
    }

    public boolean isFork() {
        return next != null && next.size() > 1;
    }

    public void addNext(String nodeId) {
        if (this.next == null) this.next = new ArrayList<>();
        if (!this.next.contains(nodeId)) this.next.add(nodeId);
    }

    // ---- waitFor ----

    public List<String> getWaitFor() { return waitFor; }
    public void setWaitFor(List<String> waitFor) { this.waitFor = waitFor; }
    public boolean isJoin() { return waitFor != null && !waitFor.isEmpty(); }

    // ---- retry fields ----

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getOutputData() { return outputData; }
    public void setOutputData(Map<String, Object> outputData) { this.outputData = outputData; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @JsonIgnore
    public boolean isAlreadySucceeded() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    @JsonIgnore
    public boolean needsExecution() {
        return !isAlreadySucceeded();
    }

    // ---- other fields ----

    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }

    public List<Branch> getBranches() { return branches; }
    public void setBranches(List<Branch> branches) { this.branches = branches; }

    public List<InputMapping> getInputMappings() { return inputMappings; }
    public void setInputMappings(List<InputMapping> inputMappings) { this.inputMappings = inputMappings; }

    public List<OutputMapping> getOutputMappings() { return outputMappings; }
    public void setOutputMappings(List<OutputMapping> outputMappings) { this.outputMappings = outputMappings; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public static class Branch {
        private String condition;

        @JsonProperty("target")
        private String targetNodeId;

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getTargetNodeId() { return targetNodeId; }
        public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }
    }
}
