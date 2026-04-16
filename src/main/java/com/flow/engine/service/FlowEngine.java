package com.flow.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.engine.exception.FlowException;
import com.flow.engine.exception.HandlerNotFoundException;
import com.flow.engine.exception.NodeNotFoundException;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowDefinition;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.FlowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core flow execution engine (Spring {@code @Service} bean).
 *
 * <h3>Usage</h3>
 * <ol>
 *   <li>Inject {@code FlowEngine} into any Spring component.</li>
 *   <li>Load a {@link FlowDefinition} from JSON (string, stream, or file).</li>
 *   <li>Call {@link #execute(FlowDefinition, FlowContext)} with an optional
 *       pre-populated context to share variables across nodes.</li>
 * </ol>
 *
 * <p>The engine resolves each node's {@code type} to a registered
 * {@link NodeHandler} bean, invokes it, and follows the routing decision
 * (explicit return value from handler → node's {@code next} field → stop).
 *
 * <p>Infinite-loop protection: execution stops after {@value #MAX_STEPS} steps.
 */
@Service
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_STEPS = 1000;

    private final ObjectMapper objectMapper;
    private final Map<String, NodeHandler> handlerRegistry = new ConcurrentHashMap<>();

    public FlowEngine(ObjectMapper objectMapper, List<NodeHandler> handlers) {
        this.objectMapper = objectMapper;
        handlers.forEach(h -> {
            handlerRegistry.put(h.getType(), h);
            log.debug("Registered handler for node type '{}'", h.getType());
        });
        log.info("FlowEngine initialized with {} handler(s): {}", handlerRegistry.size(), handlerRegistry.keySet());
    }

    // ----- Flow definition loading -------------------------------------------

    /**
     * Parse a flow definition from a JSON string.
     */
    public FlowDefinition parse(String json) {
        try {
            return objectMapper.readValue(json, FlowDefinition.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON", e);
        }
    }

    /**
     * Parse a flow definition from an input stream (e.g. classpath resource).
     */
    public FlowDefinition parse(InputStream inputStream) {
        try {
            return objectMapper.readValue(inputStream, FlowDefinition.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse flow JSON from stream", e);
        }
    }

    // ----- Execution ---------------------------------------------------------

    /**
     * Execute a flow with a new empty context.
     */
    public FlowResult execute(FlowDefinition definition) {
        return execute(definition, new FlowContext(definition.getId()));
    }

    /**
     * Execute a flow with a pre-populated context (shared variables).
     *
     * @param definition the flow definition (parsed from JSON)
     * @param context    mutable context carrying shared variables
     * @return result containing success/failure, final variables, and execution trace
     */
    public FlowResult execute(FlowDefinition definition, FlowContext context) {
        Map<String, FlowNode> nodeMap = definition.toNodeMap();
        List<String> trace = new ArrayList<>();
        String currentNodeId = definition.getStartNodeId();

        log.info("▶ Starting flow '{}' (start node: '{}')", definition.getId(), currentNodeId);

        int steps = 0;
        try {
            while (currentNodeId != null && !context.isTerminated() && steps < MAX_STEPS) {
                steps++;
                FlowNode node = nodeMap.get(currentNodeId);
                if (node == null) {
                    throw new NodeNotFoundException(definition.getId(), currentNodeId);
                }

                context.setCurrentNodeId(currentNodeId);
                trace.add(currentNodeId);

                NodeHandler handler = handlerRegistry.get(node.getType());
                if (handler == null) {
                    throw new HandlerNotFoundException(definition.getId(), node.getType());
                }

                String handlerNext = handler.handle(node, context);

                currentNodeId = handlerNext != null ? handlerNext : node.getNext();
            }

            if (steps >= MAX_STEPS) {
                log.warn("Flow '{}' exceeded maximum step limit ({})", definition.getId(), MAX_STEPS);
                return FlowResult.failure(definition.getId(), context.getAllVariables(), trace,
                        "Exceeded maximum step limit: " + MAX_STEPS);
            }

            log.info("✔ Flow '{}' completed successfully in {} step(s)", definition.getId(), steps);
            return FlowResult.success(definition.getId(), context.getAllVariables(), trace);

        } catch (FlowException e) {
            log.error("✘ Flow '{}' failed at node '{}': {}", definition.getId(), e.getNodeId(), e.getMessage());
            return FlowResult.failure(definition.getId(), context.getAllVariables(), trace, e.getMessage());
        } catch (Exception e) {
            log.error("✘ Flow '{}' failed unexpectedly: {}", definition.getId(), e.getMessage(), e);
            return FlowResult.failure(definition.getId(), context.getAllVariables(), trace, e.getMessage());
        }
    }

    // ----- Handler management ------------------------------------------------

    /**
     * Programmatically register an additional handler (useful for tests or
     * runtime-registered custom types).
     */
    public void registerHandler(NodeHandler handler) {
        handlerRegistry.put(handler.getType(), handler);
        log.info("Registered handler for node type '{}'", handler.getType());
    }

    /**
     * Check whether a handler exists for the given node type.
     */
    public boolean hasHandler(String nodeType) {
        return handlerRegistry.containsKey(nodeType);
    }
}
