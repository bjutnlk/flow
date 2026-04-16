package com.flow.engine.recorder;

/**
 * Abstraction for persisting flow execution records.
 *
 * <p>The engine calls these lifecycle methods during execution:
 * <ol>
 *   <li>{@link #onFlowStart} — flow is about to begin</li>
 *   <li>{@link #onNodeStart} — a node is about to execute</li>
 *   <li>{@link #onNodeComplete} — a node has finished (success or failure)</li>
 *   <li>{@link #onFlowComplete} — the flow has finished (success or failure)</li>
 * </ol>
 *
 * <p>Implementations can persist to a database, send to a message queue,
 * write to a file, or any combination.  The default
 * {@link InMemoryExecutionRecorder} stores everything in memory for
 * testing and local development.
 *
 * <p>To persist to a real database, implement this interface and register
 * as a Spring bean.  Example schema:
 * <pre>{@code
 * CREATE TABLE flow_execution_log (
 *   execution_id   VARCHAR(36) PRIMARY KEY,
 *   flow_id        VARCHAR(64),
 *   flow_name      VARCHAR(128),
 *   flow_version   VARCHAR(16),
 *   status         VARCHAR(16),
 *   start_time     TIMESTAMP,
 *   end_time       TIMESTAMP,
 *   total_duration_ms BIGINT,
 *   total_nodes    INT,
 *   success_nodes  INT,
 *   failed_nodes   INT,
 *   error_message  TEXT
 * );
 *
 * CREATE TABLE flow_node_execution_log (
 *   id             BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   execution_id   VARCHAR(36),
 *   node_id        VARCHAR(64),
 *   node_type      VARCHAR(32),
 *   node_name      VARCHAR(128),
 *   step_index     INT,
 *   status         VARCHAR(16),
 *   start_time     TIMESTAMP,
 *   end_time       TIMESTAMP,
 *   duration_ms    BIGINT,
 *   input_snapshot  JSON,
 *   output_snapshot JSON,
 *   error_message  TEXT,
 *   FOREIGN KEY (execution_id) REFERENCES flow_execution_log(execution_id)
 * );
 * }</pre>
 */
public interface ExecutionRecorder {

    /**
     * Called when a flow execution starts.  The returned {@link ExecutionLog}
     * is used throughout the run and passed back in {@link #onFlowComplete}.
     */
    ExecutionLog onFlowStart(ExecutionLog log);

    /**
     * Called immediately before a node's handler is executed.
     */
    void onNodeStart(ExecutionLog flowLog, NodeExecutionLog nodeLog);

    /**
     * Called immediately after a node's handler completes (success or failure).
     */
    void onNodeComplete(ExecutionLog flowLog, NodeExecutionLog nodeLog);

    /**
     * Called when the entire flow execution finishes (success or failure).
     */
    void onFlowComplete(ExecutionLog log);

    /**
     * Retrieve a previously recorded execution log by its id.
     * Returns null if not found.
     */
    ExecutionLog getExecutionLog(String executionId);
}
