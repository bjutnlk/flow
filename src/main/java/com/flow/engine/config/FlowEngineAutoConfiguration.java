package com.flow.engine.config;

/**
 * <h2>Spring Auto-wiring — How It Works</h2>
 *
 * <p>No manual configuration is needed.  All components are automatically
 * discovered via {@code @ComponentScan} from {@code @SpringBootApplication}.
 *
 * <h3>What happens at startup</h3>
 * <ol>
 *   <li>Spring scans {@code com.flow.engine} and finds all {@code @Component}
 *       classes:
 *     <ul>
 *       <li>{@code StartNodeHandler}, {@code EndNodeHandler},
 *           {@code ConditionNodeHandler}, {@code SwitchNodeHandler},
 *           {@code ForEachNodeHandler}, {@code TaskNodeHandler},
 *           {@code LogNodeHandler} — built-in handlers</li>
 *       <li>{@code SubmitFormHandler}, {@code AggregateFileHandler}
 *           — example capability handlers</li>
 *       <li>{@code InputResolver}, {@code InMemoryFileStorageService},
 *           {@code InMemoryExecutionRecorder} — infrastructure</li>
 *     </ul>
 *   </li>
 *   <li>Spring creates the {@code FlowEngine} bean, injecting:
 *     <ul>
 *       <li>{@code ObjectMapper} — from spring-boot-starter-json</li>
 *       <li>{@code InputResolver} — auto-created component</li>
 *       <li>{@code ExecutionRecorder} — InMemory by default, or your custom impl</li>
 *       <li>{@code List<NodeHandler>} — Spring collects ALL NodeHandler beans</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Adding a custom handler</h3>
 * <p>Just create a {@code @Component} implementing {@code NodeHandler}:
 * <pre>{@code
 * @Component
 * public class EmailHandler implements NodeHandler {
 *     @Override public String getType() { return "email"; }
 *     @Override public HandleResult execute(FlowNode node, FlowContext ctx) {
 *         // ...
 *         return HandleResult.none();
 *     }
 * }
 * }</pre>
 * It will be auto-registered in FlowEngine at startup.
 *
 * <h3>Using FlowEngine in your service</h3>
 * <pre>{@code
 * @Service
 * public class OrderService {
 *     private final FlowEngine flowEngine;
 *
 *     public OrderService(FlowEngine flowEngine) {
 *         this.flowEngine = flowEngine;
 *     }
 *
 *     public FlowResult processOrder(Order order) {
 *         FlowDefinition flow = flowEngine.parse(
 *             getClass().getResourceAsStream("/flows/order-flow.json"));
 *         FlowContext ctx = new FlowContext(flow.getId());
 *         ctx.setVariable("amount", order.getAmount());
 *         return flowEngine.execute(flow, ctx);
 *     }
 * }
 * }</pre>
 */
public final class FlowEngineAutoConfiguration {
    private FlowEngineAutoConfiguration() {}
}
