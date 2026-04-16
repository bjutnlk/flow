package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * A single executable node within a flow definition.
 *
 * <h3>Routing</h3>
 * <ul>
 *   <li>{@code next} — can be a single node id (string) or a list of
 *       node ids (fork). When forking, all targets are activated.</li>
 *   <li>{@code waitFor} — a list of node ids that must all be completed
 *       before this node can execute (join/converge).</li>
 * </ul>
 *
 * <p>Example fork–join:
 * <pre>{@code
 * a.next = ["b", "d"]    // a forks to b and d
 * c.waitFor = ["b", "d"] // c waits for both b and d
 * }</pre>
 * This supports the pattern: a→b,d→c where c needs outputs from both b and d.
 */
public class FlowNode {

    private String id;
    private String type;
    private String name;
    private List<String> next;
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

    /**
     * Returns the list of next node ids.  For a single-target node
     * this is a one-element list; for a fork it's multiple.
     */
    public List<String> getNext() {
        return next;
    }

    /**
     * Accepts both a single string and a list from JSON:
     * {@code "next": "b"} or {@code "next": ["b", "d"]}
     */
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

    /**
     * Convenience: returns the single next node id, or the first one
     * in a fork.  Returns null if no next is defined.
     */
    public String getFirstNext() {
        return next != null && !next.isEmpty() ? next.get(0) : null;
    }

    /**
     * Whether this node forks to multiple targets.
     */
    public boolean isFork() {
        return next != null && next.size() > 1;
    }

    /**
     * Node ids that must all be completed before this node runs.
     */
    public List<String> getWaitFor() {
        return waitFor;
    }

    public void setWaitFor(List<String> waitFor) {
        this.waitFor = waitFor;
    }

    /**
     * Whether this node requires multiple predecessors to complete first.
     */
    public boolean isJoin() {
        return waitFor != null && !waitFor.isEmpty();
    }

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
