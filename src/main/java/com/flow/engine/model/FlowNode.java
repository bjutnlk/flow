package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single executable node within a flow definition.
 *
 * <p>Nodes fall into two categories:</p>
 * <ul>
 *   <li><b>flow</b> — structural nodes that control routing (start, end, condition)</li>
 *   <li><b>capability</b> — business-logic nodes that produce typed outputs
 *       (submit_form, aggregate_file, etc.)</li>
 * </ul>
 *
 * <p>Capability nodes carry {@code inputMappings} and {@code outputMappings}
 * that the engine resolves automatically.  Inputs can reference outputs of
 * upstream nodes via {@code ${nodeId.outputName}}, cloud file IDs via
 * {@code file:xxx}, or plain context variables.
 */
public class FlowNode {

    /**
     * "flow" for routing-only nodes, "capability" for nodes that do real work.
     * Defaults to "capability" when omitted in JSON so that the common case
     * (business nodes) doesn't require an extra field.
     */
    private String category = "capability";

    private String id;
    private String type;
    private String name;
    private String next;
    private Map<String, Object> properties = new HashMap<>();
    private List<Branch> branches;
    private List<InputMapping> inputMappings;
    private List<OutputMapping> outputMappings;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isFlowNode() {
        return "flow".equals(category);
    }

    public boolean isCapabilityNode() {
        return !"flow".equals(category);
    }

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

    public String getNext() {
        return next;
    }

    public void setNext(String next) {
        this.next = next;
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

    /**
     * Conditional branch: evaluated by condition-type nodes to decide
     * which {@code targetNodeId} to jump to.
     */
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
