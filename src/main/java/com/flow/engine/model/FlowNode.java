package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * A single executable node within a flow definition.
 *
 * <h3>Graph topology in JSON</h3>
 * <p>Each node declares its predecessors via {@code prevNodes}:
 * <pre>{@code
 * { "id": "c", "prevNodes": ["b", "d"], ... }
 * }</pre>
 * This means node c comes after both b and d.
 *
 * <p>During {@link FlowDefinition#resolve()}, the engine automatically
 * computes:
 * <ul>
 *   <li>{@code next} — forward links (derived from other nodes' prevNodes)</li>
 *   <li>{@code waitFor} — set to prevNodes when a node has 2+ predecessors
 *       (join/converge point)</li>
 * </ul>
 *
 * <p>{@code next} can also be set explicitly in JSON for backward
 * compatibility or for fork scenarios ({@code "next": ["b", "d"]}).
 */
public class FlowNode {

    private String id;
    private String type;
    private String name;

    /**
     * Declared in JSON: which nodes come before this one.
     * The engine uses this to build the forward graph.
     */
    private List<String> prevNodes;

    /**
     * Computed (or explicit in JSON): which nodes come after this one.
     */
    private List<String> next;

    /**
     * Computed: node ids that must all complete before this node runs.
     * Auto-set when a node has multiple prevNodes.
     */
    @JsonIgnore
    private List<String> waitFor;

    private Map<String, Object> properties = new HashMap<>();
    private List<Branch> branches;
    private List<InputMapping> inputMappings;
    private List<OutputMapping> outputMappings;
    private String body;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // ---- prevNodes (from JSON) ----

    public List<String> getPrevNodes() {
        return prevNodes;
    }

    public void setPrevNodes(List<String> prevNodes) {
        this.prevNodes = prevNodes;
    }

    // ---- next (computed or explicit) ----

    public List<String> getNext() {
        return next;
    }

    @JsonSetter("next")
    public void setNextFromJson(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            this.next = null;
        } else if (jsonNode.isArray()) {
            this.next = new ArrayList<>();
            jsonNode.forEach(n -> this.next.add(n.asText()));
        } else {
            this.next = List.of(jsonNode.asText());
        }
    }

    public void setNext(List<String> next) {
        this.next = next;
    }

    public String getFirstNext() {
        return next != null && !next.isEmpty() ? next.get(0) : null;
    }

    public boolean isFork() {
        return next != null && next.size() > 1;
    }

    /**
     * Add a forward link (used by resolve to build next from prevNodes).
     */
    public void addNext(String nodeId) {
        if (this.next == null) {
            this.next = new ArrayList<>();
        }
        if (!this.next.contains(nodeId)) {
            this.next.add(nodeId);
        }
    }

    // ---- waitFor (computed) ----

    public List<String> getWaitFor() {
        return waitFor;
    }

    public void setWaitFor(List<String> waitFor) {
        this.waitFor = waitFor;
    }

    public boolean isJoin() {
        return waitFor != null && !waitFor.isEmpty();
    }

    // ---- other fields ----

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public List<Branch> getBranches() {
        return branches;
    }

    public void setBranches(List<Branch> branches) {
        this.branches = branches;
    }

    public List<InputMapping> getInputMappings() {
        return inputMappings;
    }

    public void setInputMappings(List<InputMapping> inputMappings) {
        this.inputMappings = inputMappings;
    }

    public List<OutputMapping> getOutputMappings() {
        return outputMappings;
    }

    public void setOutputMappings(List<OutputMapping> outputMappings) {
        this.outputMappings = outputMappings;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public static class Branch {
        private String condition;

        @JsonProperty("target")
        private String targetNodeId;

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getTargetNodeId() {
            return targetNodeId;
        }

        public void setTargetNodeId(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }
    }
}
