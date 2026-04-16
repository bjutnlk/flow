package com.flow.engine.handler;

import com.flow.engine.model.FlowContext;
import com.flow.engine.model.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outputs a log message, optionally interpolating {@code ${varName}}
 * placeholders from context variables.
 */
@Component
public class LogNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(LogNodeHandler.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    @Override
    public String getType() {
        return "log";
    }

    @Override
    public String handle(FlowNode node, FlowContext context) {
        String template = (String) node.getProperties().getOrDefault("message", "");
        String resolved = resolveTemplate(template, context);
        log.info("[Log] {}: {}", node.getId(), resolved);
        context.setVariable("_lastLog", resolved);
        return null;
    }

    private String resolveTemplate(String template, FlowContext context) {
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object val = context.getVariable(varName);
            matcher.appendReplacement(sb, val != null ? Matcher.quoteReplacement(String.valueOf(val)) : "null");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
