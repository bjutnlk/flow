package com.flow.engine.recorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link ExecutionRecorder} for testing
 * and local development.  Auto-configured as fallback when no
 * database-backed recorder is present.
 */
@Component
@ConditionalOnMissingBean(value = ExecutionRecorder.class, ignored = InMemoryExecutionRecorder.class)
public class InMemoryExecutionRecorder implements ExecutionRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryExecutionRecorder.class);

    private final Map<String, ExecutionLog> store = new ConcurrentHashMap<>();

    @Override
    public void save(ExecutionLog executionLog) {
        store.put(executionLog.getExecutionId(), executionLog);
        log.info("[Recorder] Saved execution log: executionId={}, flow='{}', status={}, "
                        + "nodes={} (ok={}, fail={}), duration={}ms",
                executionLog.getExecutionId(), executionLog.getFlowId(),
                executionLog.getStatus(),
                executionLog.getTotalNodes(), executionLog.getSuccessNodes(),
                executionLog.getFailedNodes(), executionLog.getTotalDurationMs());
    }

    @Override
    public ExecutionLog getExecutionLog(String executionId) {
        return store.get(executionId);
    }

    public int getLogCount() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
