package com.flow.engine.recorder;

/**
 * Abstraction for persisting flow execution records.
 *
 * <p>The engine collects all execution data into an {@link ExecutionLog}
 * during the run.  After the flow completes (success or failure), it
 * calls {@link #save} exactly once with the complete log.  There are
 * no per-node callbacks — this keeps the recorder simple and allows
 * implementations to do a single database transaction or batch write.
 *
 * <p>To persist to a real database, implement this interface and register
 * as a Spring {@code @Component}.  Example schema:
 * <pre>{@code
 * CREATE TABLE flow_execution_log (
 *   execution_id    VARCHAR(36) PRIMARY KEY,
 *   flow_id         VARCHAR(64),
 *   flow_name       VARCHAR(128),
 *   flow_version    VARCHAR(16),
 *   status          VARCHAR(16),
 *   start_time      TIMESTAMP,
 *   end_time        TIMESTAMP,
 *   total_duration_ms BIGINT,
 *   total_nodes     INT,
 *   success_nodes   INT,
 *   failed_nodes    INT,
 *   error_message   TEXT
 * );
 *
 * CREATE TABLE flow_node_execution_log (
 *   id              BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   execution_id    VARCHAR(36),
 *   node_id         VARCHAR(64),
 *   node_type       VARCHAR(32),
 *   node_name       VARCHAR(128),
 *   step_index      INT,
 *   status          VARCHAR(16),
 *   start_time      TIMESTAMP,
 *   end_time        TIMESTAMP,
 *   duration_ms     BIGINT,
 *   input_snapshot  JSON,
 *   output_snapshot JSON,
 *   error_message   TEXT,
 *   FOREIGN KEY (execution_id) REFERENCES flow_execution_log(execution_id)
 * );
 * }</pre>
 */
public interface ExecutionRecorder {

    /**
     * Persist a completed execution log (containing both flow-level
     * summary and all node-level details).  Called exactly once per
     * flow execution, after all nodes have finished.
     */
    void save(ExecutionLog log);

    /**
     * Retrieve a previously saved execution log by its id.
     * Returns null if not found.
     */
    ExecutionLog getExecutionLog(String executionId);
}
