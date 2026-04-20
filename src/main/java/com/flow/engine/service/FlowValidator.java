package com.flow.engine.service;

import com.flow.engine.exception.FlowValidationException;
import com.flow.engine.model.FlowDefinition;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Pre-execution validator for flow definitions.
 *
 * <p>Checks performed (all at once, returns all errors together):
 * <ol>
 *   <li><b>Version</b> — must be present and >= MIN_VERSION</li>
 *   <li><b>Nodes non-empty</b> — at least one node required</li>
 *   <li><b>No duplicate IDs</b></li>
 *   <li><b>Start node</b> — must exist and be a valid node id</li>
 *   <li><b>End node</b> — at least one node of type "end" required</li>
 *   <li><b>Broken references</b> — prevNodes, next, branches must
 *       reference existing node ids</li>
 *   <li><b>Reachability</b> — all nodes must be reachable from start</li>
 *   <li><b>End reachability</b> — at least one end node must be reachable</li>
 *   <li><b>Cycle detection</b> — no cycles allowed (would cause infinite loop)</li>
 * </ol>
 *
 * <p>Call {@link #validate} before execution.  It throws
 * {@link FlowValidationException} with all errors collected, so
 * the caller can fix everything in one pass.
 */
@Component
public class FlowValidator {

    private static final Logger log = LoggerFactory.getLogger(FlowValidator.class);

    public static final String MIN_VERSION = "1.0";

    /**
     * Validate a flow definition.  Throws if any problems are found.
     */
    public void validate(FlowDefinition definition) {
        List<String> errors = new ArrayList<>();

        checkVersion(definition, errors);
        checkNodesNotEmpty(definition, errors);

        if (!errors.isEmpty() && (definition.getNodes() == null || definition.getNodes().isEmpty())) {
            throw new FlowValidationException(definition.getId(), errors);
        }

        Map<String, FlowNode> nodeMap = definition.toNodeMap();

        checkDuplicateIds(definition, errors);
        checkStartNode(definition, nodeMap, errors);
        checkEndNode(definition, nodeMap, errors);
        checkBrokenReferences(definition, nodeMap, errors);

        if (errors.isEmpty()) {
            checkReachability(definition, nodeMap, errors);
            checkCycles(definition, nodeMap, errors);
        }

        if (!errors.isEmpty()) {
            log.warn("Flow '{}' validation failed: {}", definition.getId(), errors);
            throw new FlowValidationException(definition.getId(), errors);
        }

        log.debug("Flow '{}' passed all validation checks", definition.getId());
    }

    // ---- individual checks --------------------------------------------------

    private void checkVersion(FlowDefinition def, List<String> errors) {
        String version = def.getVersion();
        if (version == null || version.isBlank()) {
            errors.add("Version is required but not set");
            return;
        }
        if (FlowEngine.compareVersions(version, MIN_VERSION) < 0) {
            errors.add("Version '" + version + "' is not supported (minimum: " + MIN_VERSION + ")");
        }
    }

    private void checkNodesNotEmpty(FlowDefinition def, List<String> errors) {
        if (def.getNodes() == null || def.getNodes().isEmpty()) {
            errors.add("Flow has no nodes");
        }
    }

    private void checkDuplicateIds(FlowDefinition def, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (FlowNode node : def.getNodes()) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("Node found with null/empty id");
            } else if (!seen.add(node.getId())) {
                errors.add("Duplicate node id: '" + node.getId() + "'");
            }
        }
    }

    private void checkStartNode(FlowDefinition def, Map<String, FlowNode> nodeMap, List<String> errors) {
        String startId = def.getStartNodeId();
        if (startId == null || startId.isBlank()) {
            errors.add("Start node not defined (no startNodeId and unable to auto-detect)");
            return;
        }
        if (!nodeMap.containsKey(startId)) {
            errors.add("Start node '" + startId + "' does not exist in the node list");
        }
    }

    private void checkEndNode(FlowDefinition def, Map<String, FlowNode> nodeMap, List<String> errors) {
        boolean hasEnd = def.getNodes().stream()
                .anyMatch(n -> "end".equals(n.getType()));
        if (!hasEnd) {
            errors.add("Flow must have at least one node of type 'end'");
        }
    }

    private void checkBrokenReferences(FlowDefinition def, Map<String, FlowNode> nodeMap, List<String> errors) {
        for (FlowNode node : def.getNodes()) {
            if (node.getNext() != null) {
                for (String nextId : node.getNext()) {
                    if (!nodeMap.containsKey(nextId)) {
                        errors.add("Node '" + node.getId() + "' references non-existent next node '" + nextId + "'");
                    }
                }
            }
            if (node.getPrevNodes() != null) {
                for (String prevId : node.getPrevNodes()) {
                    if (!nodeMap.containsKey(prevId)) {
                        errors.add("Node '" + node.getId() + "' references non-existent prevNode '" + prevId + "'");
                    }
                }
            }
            if (node.getBranches() != null) {
                for (FlowNode.Branch branch : node.getBranches()) {
                    if (branch.getTargetNodeId() != null && !nodeMap.containsKey(branch.getTargetNodeId())) {
                        errors.add("Node '" + node.getId() + "' branch references non-existent target '" + branch.getTargetNodeId() + "'");
                    }
                }
            }
        }
    }

    private void checkReachability(FlowDefinition def, Map<String, FlowNode> nodeMap, List<String> errors) {
        String startId = def.getStartNodeId();
        if (startId == null || !nodeMap.containsKey(startId)) {
            return;
        }

        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startId);

        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!reachable.add(id)) continue;

            FlowNode node = nodeMap.get(id);
            if (node == null) continue;

            if (node.getNext() != null) {
                for (String nextId : node.getNext()) {
                    if (!reachable.contains(nextId)) {
                        queue.add(nextId);
                    }
                }
            }
            if (node.getBranches() != null) {
                for (FlowNode.Branch branch : node.getBranches()) {
                    if (branch.getTargetNodeId() != null && !reachable.contains(branch.getTargetNodeId())) {
                        queue.add(branch.getTargetNodeId());
                    }
                }
            }
        }

        for (FlowNode node : def.getNodes()) {
            if (!reachable.contains(node.getId())) {
                errors.add("Node '" + node.getId() + "' is unreachable from start node '" + startId + "'");
            }
        }

        boolean endReachable = def.getNodes().stream()
                .filter(n -> "end".equals(n.getType()))
                .anyMatch(n -> reachable.contains(n.getId()));
        if (!endReachable) {
            errors.add("No end node is reachable from start node '" + startId + "'");
        }
    }

    private void checkCycles(FlowDefinition def, Map<String, FlowNode> nodeMap, List<String> errors) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (FlowNode node : def.getNodes()) {
            if (!visited.contains(node.getId())) {
                if (hasCycleDfs(node.getId(), nodeMap, visited, inStack)) {
                    errors.add("Flow contains a cycle (infinite loop detected)");
                    return;
                }
            }
        }
    }

    private boolean hasCycleDfs(String nodeId, Map<String, FlowNode> nodeMap,
                                Set<String> visited, Set<String> inStack) {
        visited.add(nodeId);
        inStack.add(nodeId);

        FlowNode node = nodeMap.get(nodeId);
        if (node != null) {
            List<String> successors = collectSuccessors(node);
            for (String succ : successors) {
                if (inStack.contains(succ)) {
                    return true;
                }
                if (!visited.contains(succ) && nodeMap.containsKey(succ)) {
                    if (hasCycleDfs(succ, nodeMap, visited, inStack)) {
                        return true;
                    }
                }
            }
        }

        inStack.remove(nodeId);
        return false;
    }

    private List<String> collectSuccessors(FlowNode node) {
        List<String> successors = new ArrayList<>();
        if (node.getNext() != null) {
            successors.addAll(node.getNext());
        }
        if (node.getBranches() != null) {
            for (FlowNode.Branch branch : node.getBranches()) {
                if (branch.getTargetNodeId() != null) {
                    successors.add(branch.getTargetNodeId());
                }
            }
        }
        return successors;
    }
}
