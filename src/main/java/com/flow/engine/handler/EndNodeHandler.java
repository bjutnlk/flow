package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EndNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(EndNodeHandler.class);

    @Override
    public String getType() {
        return "end";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        log.info("[End] Flow '{}' ending at node '{}'", context.getFlowId(), node.getId());
        context.terminate();
        return HandleResult.none();
    }
}
