package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generic task node — copies all declared properties into the context
 * (property key → context variable).
 */
@Component
public class TaskNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskNodeHandler.class);

    @Override
    public String getType() {
        return "task";
    }

    @Override
    public String handle(FlowNode node, FlowContext context) {
        log.info("[Task] Executing node '{}' ({})", node.getId(), node.getName());
        node.getProperties().forEach(context::setVariable);
        return null;
    }
}
