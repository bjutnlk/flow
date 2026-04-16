package com.flow.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowException;
import com.flow.engine.exception.HandlerNotFoundException;
import com.flow.engine.exception.NodeNotFoundException;
import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.*;
import com.flow.engine.recorder.ExecutionLog;
import com.flow.engine.recorder.ExecutionRecorder;
import com.flow.engine.recorder.NodeExecutionLog;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core flow execution engine.
 *
 * <h3>Retry support</h3>
 * <p>When retrying a failed flow, pass a JSON where previously succeeded
 * nodes carry {@code "status": "SUCCESS"} and {@code "outputData": {...}}.
 * The engine skips those nodes (restores their output into context) and
 * re-executes from the failed/pending nodes onward.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_STEPS = 1000;

    public static final String CURRENT_VERSION = "2.0";

    private final ObjectMapper objectMapper;
    private final InputResolver inputResolver;
    private final ExecutionRecorder executionRecorder;
    private final FlowValidator flowValidator;
    private final Map<String, NodeHandler> handlerRegistry = new ConcurrentHashMap<>();

    public FlowEngine(ObjectMapper objectMapper,
                      InputResolver inputResolver,
                      ExecutionRecorder executionRecorder,
                      FlowValidator flowValidator,
                      List<NodeHandler> handlers) {
        this.objectMapper = objectMapper;
        this.inputResolver = inputResolver;
        this.executionRecorder = executionRecorder;
        this.flowValidator = flowValidator;
        handlers.forEach(h -> {
            handlerRegistry.put(h.getType(), h);
            log.debug("Registered handler for node type '{}'", h.getType());
        });
        log.info("FlowEngine initialized with {} handler(s): {}", handlerRegistry.size(), handlerRegistry.keySet());
    }

    // ---- parsing ------------------------------------------------------------

    public FlowDefinition parse(String json) {
        try {
            FlowDefinition def = objectMapper.readValue(json, FlowDefinition.class);
            def.resolve();
            return def;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON", e);
        }
    }

    public FlowDefinition parse(InputStream inputStream) {
        try {
            FlowDefinition def = objectMapper.readValue(inputStream, FlowDefinition.class);
            def.resolve();
            return def;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON from stream", e);
        }
    }

    // ---- version comparison -------------------------------------------------

    public static int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            int aN = i < aParts.length ? parseSegment(aParts[i]) : 0;
            int bN = i < bParts.length ? parseSegment(bParts[i]) : 0;
            if (aN != bN) return Integer.compare(aN, bN);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ---- execution ----------------------------------------------------------

    public FlowResult execute(FlowDefinition definition) {
        return execute(definition, new FlowContext(definition.getId()));
    }

    public FlowResult execute(FlowDefinition definition, FlowContext context) {
        flowValidator.validate(definition);

        Map<String, FlowNode> nodeMap = definition.toNodeMap();
        List<String> trace = new ArrayList<>();
        Set<String> completedNodes = new LinkedHashSet<>();
        Deque<String> readyQueue = new ArrayDeque<>();

        ExecutionLog executionLog = new ExecutionLog(
                definition.getId(), definition.getName(), definition.getVersion());

        // Restore previously succeeded nodes from JSON (for retry)
        restoreSucceededNodes(nodeMap, context, completedNodes, executionLog);

        log.info("▶ Starting flow '{}' v{} (executionId={}, restored={} node(s))",
                definition.getId(), definition.getVersion(),
                executionLog.getExecutionId(), completedNodes.size());

        readyQueue.add(definition.getStartNodeId());
        int steps = 0;

        try {
            while (!readyQueue.isEmpty() && !context.isTerminated() && steps < MAX_STEPS) {
                String currentNodeId = readyQueue.poll();

                if (completedNodes.contains(currentNodeId)) {
                    FlowNode doneNode = nodeMap.get(currentNodeId);
                    if (doneNode != null) {
                        enqueueSuccessors(doneNode, null, readyQueue);
                    }
                    continue;
                }

                FlowNode node = nodeMap.get(currentNodeId);
                if (node == null) {
                    throw new NodeNotFoundException(definition.getId(), currentNodeId);
                }

                if (node.isJoin() && !completedNodes.containsAll(node.getWaitFor())) {
                    readyQueue.addLast(currentNodeId);
                    if (readyQueue.size() == 1) {
                        List<String> missing = new ArrayList<>(node.getWaitFor());
                        missing.removeAll(completedNodes);
                        throw new FlowException(
                                "Deadlock: node '" + currentNodeId + "' waits for " + missing
                                        + " but they are not reachable",
                                definition.getId(), currentNodeId);
                    }
                    continue;
                }

                steps++;
                context.setCurrentNodeId(currentNodeId);
                trace.add(currentNodeId);

                NodeHandler handler = handlerRegistry.get(node.getType());
                if (handler == null) {
                    throw new HandlerNotFoundException(definition.getId(), node.getType());
                }

                NodeExecutionLog nodeLog = new NodeExecutionLog(
                        node.getId(), node.getType(), node.getName(), steps);

                resolveInputs(node, context);
                nodeLog.setInputSnapshot(context.getResolvedInputs());

                try {
                    HandleResult result = handler.execute(node, context);

                    Map<String, Object> outputSnapshot = null;
                    if (result.hasOutput()) {
                        context.setNodeOutput(currentNodeId, result.getOutput());
                        publishOutputAsVariables(currentNodeId, result.getOutput(), context);
                        outputSnapshot = toOutputSnapshot(result.getOutput());
                    }

                    nodeLog.markSuccess(outputSnapshot);
                    completedNodes.add(currentNodeId);

                    enqueueSuccessors(node, result, readyQueue);

                } catch (Exception e) {
                    nodeLog.markFailed(e.getMessage());
                    executionLog.addNodeLog(nodeLog);
                    throw e;
                }

                executionLog.addNodeLog(nodeLog);
                context.clearResolvedInputs();
            }

            if (steps >= MAX_STEPS) {
                String msg = "Exceeded maximum step limit: " + MAX_STEPS;
                executionLog.markFailed(msg);
                executionRecorder.save(executionLog);
                return FlowResult.failure(definition.getId(), context.getAllVariables(),
                        trace, msg, executionLog.getExecutionId());
            }

            log.info("✔ Flow '{}' completed successfully in {} step(s)", definition.getId(), steps);
            executionLog.markSuccess();
            executionRecorder.save(executionLog);

            Map<String, Object> returnValues = extractReturnValues(completedNodes, nodeMap, context);
            return FlowResult.success(definition.getId(), context.getAllVariables(),
                    returnValues, trace, executionLog.getExecutionId());

        } catch (FlowException e) {
            log.error("✘ Flow '{}' failed at node '{}': {}", definition.getId(), e.getNodeId(), e.getMessage());
            executionLog.markFailed(e.getMessage());
            executionRecorder.save(executionLog);
            return FlowResult.failure(definition.getId(), context.getAllVariables(),
                    trace, e.getMessage(), executionLog.getExecutionId());
        } catch (Exception e) {
            log.error("✘ Flow '{}' failed unexpectedly: {}", definition.getId(), e.getMessage(), e);
            executionLog.markFailed(e.getMessage());
            executionRecorder.save(executionLog);
            return FlowResult.failure(definition.getId(), context.getAllVariables(),
                    trace, e.getMessage(), executionLog.getExecutionId());
        }
    }

    // ---- handler management -------------------------------------------------

    public void registerHandler(NodeHandler handler) {
        handlerRegistry.put(handler.getType(), handler);
    }

    public boolean hasHandler(String nodeType) {
        return handlerRegistry.containsKey(nodeType);
    }

    // ---- internal -----------------------------------------------------------

    /**
     * On retry: scan all nodes for status=SUCCESS, restore their outputData
     * into the context so downstream nodes can reference them via ${nodeId.field}.
     */
    private void restoreSucceededNodes(Map<String, FlowNode> nodeMap,
                                       FlowContext context,
                                       Set<String> completedNodes,
                                       ExecutionLog executionLog) {
        for (FlowNode node : nodeMap.values()) {
            if (!node.isAlreadySucceeded()) {
                continue;
            }

            String nodeId = node.getId();
            completedNodes.add(nodeId);

            if (node.getOutputData() != null && !node.getOutputData().isEmpty()) {
                NodeOutput.Builder builder = NodeOutput.builder();
                node.getOutputData().forEach((k, v) -> {
                    builder.add(k, NodeOutput.DataType.STRING, v);
                    context.setVariable(nodeId + "." + k, v);
                });
                NodeOutput output = builder.build();
                context.setNodeOutput(nodeId, output);
            }

            if (node.getProperties() != null) {
                node.getProperties().forEach(context::setVariable);
            }

            NodeExecutionLog nodeLog = new NodeExecutionLog(
                    nodeId, node.getType(), node.getName(), 0);
            nodeLog.markSkipped();
            executionLog.addNodeLog(nodeLog);

            log.debug("Restored succeeded node '{}' with outputData: {}",
                    nodeId, node.getOutputData());
        }
    }

    private void enqueueSuccessors(FlowNode node, HandleResult result,
                                   Deque<String> readyQueue) {
        if (result != null && result.hasExplicitRoute()) {
            readyQueue.add(result.getNextNodeId());
        } else if (node.getNext() != null) {
            readyQueue.addAll(node.getNext());
        }
    }

    private void resolveInputs(FlowNode node, FlowContext context) {
        List<InputMapping> mappings = node.getInputMappings();
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        Map<String, Object> resolved = inputResolver.resolve(mappings, context);
        context.setResolvedInputs(resolved);
    }

    private void publishOutputAsVariables(String nodeId, NodeOutput output, FlowContext context) {
        output.getEntries().forEach((name, entry) ->
                context.setVariable(nodeId + "." + name, entry.getValue()));
    }

    private Map<String, Object> extractReturnValues(Set<String> completedNodes,
                                                     Map<String, FlowNode> nodeMap,
                                                     FlowContext context) {
        for (String nodeId : completedNodes) {
            FlowNode node = nodeMap.get(nodeId);
            if (node != null && "end".equals(node.getType())) {
                NodeOutput output = context.getNodeOutput(nodeId);
                if (output != null) {
                    Map<String, Object> rv = new LinkedHashMap<>();
                    output.getEntries().forEach((k, v) -> rv.put(k, v.getValue()));
                    return rv;
                }
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> toOutputSnapshot(NodeOutput output) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        output.getEntries().forEach((name, entry) ->
                snapshot.put(name, entry.toString()));
        return snapshot;
    }
}
