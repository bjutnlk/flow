package com.flow.engine.handler.capability;

import com.flow.engine.handler.NodeHandler;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;

/**
 * Base class for capability (business-logic) handlers that produce typed outputs.
 *
 * <p>Subclasses override {@link #execute(FlowNode, FlowContext)} to perform
 * the actual work and return a {@link NodeOutput}.  Routing is handled by
 * the default {@link #handle} implementation which simply returns {@code null}
 * (follow the node's {@code next} pointer).  Override {@code handle} only
 * if the capability needs to influence routing.
 */
public abstract class CapabilityNodeHandler implements NodeHandler {

    @Override
    public String handle(FlowNode node, FlowContext context) {
        return null;
    }

    @Override
    public abstract NodeOutput execute(FlowNode node, FlowContext context);
}
