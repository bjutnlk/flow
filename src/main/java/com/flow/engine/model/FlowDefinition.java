package com.flow.engine.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Top-level flow definition deserialized from JSON.
 *
 * <pre>{@code
 * {
 *   "id": "order-flow",
 *   "name": "Order Processing",
 *   "startNodeId": "validate",
 *   "nodes": [ ... ]
 * }
 * }</pre>
 */
public class FlowDefinition {

    private String id;
    private String name;
    private String startNodeId;
    private List<FlowNode> nodes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStartNodeId() {
        return startNodeId;
    }

    public void setStartNodeId(String startNodeId) {
        this.startNodeId = startNodeId;
    }

    public List<FlowNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<FlowNode> nodes) {
        this.nodes = nodes;
    }

    /**
     * Build a lookup map keyed by node id for O(1) access during execution.
     */
    public Map<String, FlowNode> toNodeMap() {
        return nodes.stream()
                .collect(Collectors.toMap(FlowNode::getId, Function.identity()));
    }
}
