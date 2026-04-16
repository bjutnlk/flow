package com.flow.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single executable node within a flow definition.
 *
 * <p>Each node has a unique {@code id}, a {@code type} that determines which
 * {@link com.flow.engine.handler.NodeHandler} processes it, and optional
 * routing information ({@code next} / {@code branches}) for sequential or
 * conditional transitions.
 */
public class FlowNode {

    private String id;
    private String type;
    private String name;
    private String next;
    private Map<String, Object> properties = new HashMap<>();
    private List<Branch> branches;

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
