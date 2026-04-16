package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single executable node within a flow definition.
 *
 * <p>All nodes are equal in the engine's execution loop — each node is
 * dispatched to its handler, which may produce output, read inputs, and
 * influence routing.  There is no hard category distinction; the
 * {@code type} field alone determines which handler processes the node.
 *
 * <p>Convention (not enforced):
 * <ul>
 *   <li><b>Generic / control-flow types</b> — {@code start}, {@code end},
 *       {@code condition}, {@code switch}, {@code foreach}: business-agnostic
 *       nodes shipped with the engine.</li>
 *   <li><b>Capability types</b> — {@code submit_form}, {@code aggregate_file},
 *       etc.: business-specific nodes registered by the application.</li>
 * </ul>
 *
 * <p>Any node can carry {@code inputMappings} and {@code outputMappings}
 * — the engine resolves them uniformly before/after execution.
 */
public class FlowNode {

    private String id;
    private String type;
    private String name;
    private String next;
    private Map<String, Object> properties = new HashMap<>();
    private List<Branch> branches;
    private List<InputMapping> inputMappings;
    private List<OutputMapping> outputMappings;

    /**
     * Optional: body node id for block-structured handlers like
     * {@code foreach}.  Points to the first node of the sub-chain
     * that should be executed per iteration.
     */
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

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    /**
     * Conditional/switch branch: evaluated to decide which
     * {@code targetNodeId} to jump to.
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
