package com.flow.engine.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared execution context that travels through every node in a flow run.
 *
 * <p>Two storage tiers:</p>
 * <ol>
 *   <li><b>Variables</b> — flat key-value pairs set via
 *       {@link #setVariable}/{@link #getVariable}.  Backward-compatible
 *       with the original API; referenced as {@code ${key}}.</li>
 *   <li><b>Node outputs</b> — structured {@link NodeOutput} objects
 *       stored per node id after each handler completes.  Referenced as
 *       {@code ${nodeId.outputName}}.  This enables the A→B→C scenario
 *       where C's input is A's output file.</li>
 * </ol>
 */
public class FlowContext {

    private final String flowId;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, NodeOutput> nodeOutputs = new ConcurrentHashMap<>();
    private final Map<String, Object> resolvedInputs = new ConcurrentHashMap<>();
    private String currentNodeId;
    private boolean terminated;

    public FlowContext(String flowId) {
        this.flowId = flowId;
    }

    public String getFlowId() {
        return flowId;
    }

    // ---- flat variables (backward compat) ----

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key) {
        return (T) variables.get(key);
    }

    public <T> T getVariable(String key, T defaultValue) {
        @SuppressWarnings("unchecked")
        T value = (T) variables.get(key);
        return value != null ? value : defaultValue;
    }

    public boolean hasVariable(String key) {
        return variables.containsKey(key);
    }

    public void removeVariable(String key) {
        variables.remove(key);
    }

    public Map<String, Object> getAllVariables() {
        return new ConcurrentHashMap<>(variables);
    }

    // ---- per-node structured outputs ----

    public void setNodeOutput(String nodeId, NodeOutput output) {
        nodeOutputs.put(nodeId, output);
    }

    public NodeOutput getNodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    /**
     * Resolve {@code ${nodeId.field}} — returns the output value of a
     * specific field from a previously executed node.
     */
    public Object getNodeOutputValue(String nodeId, String field) {
        NodeOutput output = nodeOutputs.get(nodeId);
        return output != null ? output.getValue(field) : null;
    }

    public Map<String, NodeOutput> getAllNodeOutputs() {
        return new ConcurrentHashMap<>(nodeOutputs);
    }

    // ---- resolved inputs for current node ----

    public void setResolvedInputs(Map<String, Object> inputs) {
        resolvedInputs.clear();
        resolvedInputs.putAll(inputs);
    }

    @SuppressWarnings("unchecked")
    public <T> T getResolvedInput(String name) {
        return (T) resolvedInputs.get(name);
    }

    public Map<String, Object> getResolvedInputs() {
        return new ConcurrentHashMap<>(resolvedInputs);
    }

    public void clearResolvedInputs() {
        resolvedInputs.clear();
    }

    // ---- execution state ----

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public void terminate() {
        this.terminated = true;
    }

    @Override
    public String toString() {
        return "FlowContext{flowId='" + flowId + "', currentNode='" + currentNodeId
                + "', vars=" + variables
                + ", outputs=" + nodeOutputs.keySet() + '}';
    }
}
