package com.flow.engine.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared execution context that travels through every node in a flow run.
 *
 * <p>Nodes read and write variables via {@link #setVariable}/{@link #getVariable},
 * making data produced by an earlier node available to later ones without
 * external coupling.
 */
public class FlowContext {

    private final String flowId;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private String currentNodeId;
    private boolean terminated;

    public FlowContext(String flowId) {
        this.flowId = flowId;
    }

    public String getFlowId() {
        return flowId;
    }

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

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public boolean isTerminated() {
        return terminated;
    }

    /**
     * Signal that the flow should stop after the current node completes.
     */
    public void terminate() {
        this.terminated = true;
    }

    @Override
    public String toString() {
        return "FlowContext{flowId='" + flowId + "', currentNode='" + currentNodeId
                + "', vars=" + variables + '}';
    }
}
