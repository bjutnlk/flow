package com.flow.engine.handler;

import com.flow.engine.model.FileReference;
import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import com.flow.engine.model.NodeOutput;
import com.flow.engine.resolve.InputResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outputs a log message, interpolating {@code ${varName}} and
 * {@code ${nodeId.field}} placeholders from the context.
 */
@Component
public class LogNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(LogNodeHandler.class);
    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([\\w.]+)}");

    private final InputResolver inputResolver;

    public LogNodeHandler(InputResolver inputResolver) {
        this.inputResolver = inputResolver;
    }

    @Override
    public String getType() {
        return "log";
    }

    @Override
    public String handle(FlowNode node, FlowContext context) {
        String template = (String) node.getProperties().getOrDefault("message", "");
        String resolved = (String) inputResolver.resolveSource(template, null, context);
        log.info("[Log] {}: {}", node.getId(), resolved);
        context.setVariable("_lastLog", resolved);
        return null;
    }
}
