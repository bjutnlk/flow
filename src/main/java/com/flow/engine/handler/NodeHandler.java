package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;

/**
 * Strategy interface for node execution.
 *
 * <p>Implementations are registered as Spring beans; the engine discovers them
 * automatically via {@link #getType()} and dispatches each node to the
 * matching handler.
 *
 * <p>A handler can influence routing by returning a specific next-node id,
 * or {@code null} to fall back to the node's default {@code next} field.
 */
public interface NodeHandler {

    /**
     * The node type this handler is responsible for (e.g. "task", "condition", "log").
     */
    String getType();

    /**
     * Execute the logic for the given node.
     *
     * @param node    the current node definition (read-only by convention)
     * @param context shared mutable context
     * @return an explicit next-node id, or {@code null} to use {@link FlowNode#getNext()}
     */
    String handle(FlowNode node, FlowContext context);
}
