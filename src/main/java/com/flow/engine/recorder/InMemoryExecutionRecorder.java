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
    public ExecutionLog onFlowStart(ExecutionLog executionLog) {
        store.put(executionLog.getExecutionId(), executionLog);
        log.info("[Recorder] Flow started: executionId={}, flowId={}, version={}",
                executionLog.getExecutionId(), executionLog.getFlowId(), executionLog.getFlowVersion());
        return executionLog;
    }

    @Override
    public void onNodeStart(ExecutionLog flowLog, NodeExecutionLog nodeLog) {
        log.debug("[Recorder] Node starting: #{} {} ({})",
                nodeLog.getStepIndex(), nodeLog.getNodeId(), nodeLog.getNodeType());
    }

    @Override
    public void onNodeComplete(ExecutionLog flowLog, NodeExecutionLog nodeLog) {
        log.debug("[Recorder] Node completed: #{} {} → {} ({}ms)",
                nodeLog.getStepIndex(), nodeLog.getNodeId(),
                nodeLog.getStatus(), nodeLog.getDurationMs());
    }

    @Override
    public void onFlowComplete(ExecutionLog executionLog) {
        store.put(executionLog.getExecutionId(), executionLog);
        log.info("[Recorder] Flow completed: executionId={}, status={}, " +
                        "nodes={} (ok={}, fail={}), duration={}ms",
                executionLog.getExecutionId(), executionLog.getStatus(),
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
