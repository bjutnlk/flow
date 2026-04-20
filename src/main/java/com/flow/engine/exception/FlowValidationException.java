package com.flow.engine.exception;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when a flow definition fails pre-execution validation.
 * Carries a list of all detected problems so the caller can fix
 * them in one pass rather than hitting them one at a time.
 */
public class FlowValidationException extends FlowException {

    private final List<String> errors;

    public FlowValidationException(String flowId, List<String> errors) {
        super("Flow '" + flowId + "' validation failed with " + errors.size()
                        + " error(s): " + errors,
                flowId, null);
        this.errors = Collections.unmodifiableList(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
