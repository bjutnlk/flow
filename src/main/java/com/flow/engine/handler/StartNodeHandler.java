package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StartNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(StartNodeHandler.class);

    @Override
    public String getType() {
        return "start";
    }

    @Override
    public HandleResult execute(FlowNode node, FlowContext context) {
        log.info("[Start] Flow '{}' started at node '{}'", context.getFlowId(), node.getId());
        return HandleResult.none();
    }
}
