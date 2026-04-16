package com.flow.engine.model;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Top-level flow definition deserialized from JSON.
 *
 * <p>After JSON parsing, call {@link #resolve()} to build the execution
 * graph from each node's {@code prevNodes} declarations.
 *
 * <h3>JSON format</h3>
 * <pre>{@code
 * {
 *   "version": "2.0",
 *   "id": "my-flow",
 *   "name": "My Flow",
 *   "nodes": [
 *     { "id": "a", "type": "task", "name": "A" },
 *     { "id": "b", "type": "task", "name": "B", "prevNodes": ["a"] },
 *     { "id": "c", "type": "task", "name": "C", "prevNodes": ["a"] },
 *     { "id": "d", "type": "task", "name": "D", "prevNodes": ["b", "c"] }
 *   ]
 * }
 * }</pre>
 *
 * <p>After resolve():
 * <ul>
 *   <li>{@code a.next = ["b", "c"]} — computed from b and c declaring prevNodes=["a"]</li>
 *   <li>{@code d.waitFor = ["b", "c"]} — auto-detected because d has 2 prevNodes</li>
 *   <li>{@code startNodeId = "a"} — auto-detected as the node with no prevNodes</li>
 * </ul>
 */
public class FlowDefinition {

    private String version;
    private String id;
    private String name;
    private String startNodeId;
    private List<FlowNode> nodes;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

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

    public Map<String, FlowNode> toNodeMap() {
        Map<String, FlowNode> map = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            map.put(node.getId(), node);
        }
        return map;
    }

    /**
     * Analyze the graph topology from {@code prevNodes} declarations
     * and compute forward links ({@code next}), join points
     * ({@code waitFor}), and the start node.
     *
     * <p>This method is idempotent — safe to call multiple times.
     *
     * <h4>Rules</h4>
     * <ol>
     *   <li>For each node N with {@code prevNodes = [X, Y]}, add N
     *       to X.next and Y.next (forward links). This does NOT
     *       overwrite any {@code next} that was set explicitly in JSON.</li>
     *   <li>If N has 2+ entries in {@code prevNodes}, set
     *       {@code N.waitFor = prevNodes} (join point).</li>
     *   <li>If {@code startNodeId} is not set, auto-detect the node
     *       with no prevNodes as the start.</li>
     * </ol>
     */
    public void resolve() {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }

        Map<String, FlowNode> nodeMap = toNodeMap();

        for (FlowNode node : nodes) {
            List<String> prev = node.getPrevNodes();
            if (prev == null || prev.isEmpty()) {
                continue;
            }

            // Build forward links from prevNodes
            for (String prevId : prev) {
                FlowNode prevNode = nodeMap.get(prevId);
                if (prevNode != null) {
                    prevNode.addNext(node.getId());
                }
            }

            // Auto-detect join: 2+ predecessors → need to wait for all
            if (prev.size() > 1) {
                node.setWaitFor(new ArrayList<>(prev));
            }
        }

        // Auto-detect start node: the node with no prevNodes and not referenced
        // as a successor by anyone via prevNodes
        if (startNodeId == null || startNodeId.isBlank()) {
            Set<String> hasIncoming = new HashSet<>();
            for (FlowNode node : nodes) {
                if (node.getPrevNodes() != null) {
                    hasIncoming.add(node.getId());
                }
            }
            for (FlowNode node : nodes) {
                if (!hasIncoming.contains(node.getId())) {
                    this.startNodeId = node.getId();
                    break;
                }
            }
        }
    }
}
