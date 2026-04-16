package com.flow.engine.handler.capability;

import com.flow.engine.handler.HandleResult;
import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;

/**
 * Convenience base class for handlers that always produce output and
 * never override routing (follow {@code node.next}).
 *
 * <p>Subclasses implement {@link #doExecute} and return a {@link NodeOutput};
 * routing is handled automatically.
 */
public abstract class CapabilityNodeHandler implements NodeHandler {

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        NodeOutput output = doExecute(node, context);
        return HandleResult.output(output);
    }

    /**
     * Perform the business logic and return typed output.
     */
    protected abstract NodeOutput doExecute(FlowNode node, FlowContext context);
}
