/*
 * To change this license header, choose License Headers in Project Properties. To change this
 * template file, choose Tools | Templates and open the template in the editor.
 */

package com.softavail.commsrouter.app;

import com.google.common.collect.Maps;

import com.softavail.commsrouter.api.dto.model.AgentState;
import com.softavail.commsrouter.api.dto.model.RouterObjectId;
import com.softavail.commsrouter.api.dto.model.TaskDto;
import com.softavail.commsrouter.api.dto.model.TaskState;
import com.softavail.commsrouter.api.exception.CommsRouterException;
import com.softavail.commsrouter.api.exception.NotFoundException;
import com.softavail.commsrouter.api.interfaces.TaskEventHandler;
import com.softavail.commsrouter.domain.Agent;
import com.softavail.commsrouter.domain.ApiObject;
import com.softavail.commsrouter.domain.Plan;
import com.softavail.commsrouter.domain.Queue;
import com.softavail.commsrouter.domain.Route;
import com.softavail.commsrouter.domain.Task;
import com.softavail.commsrouter.domain.dto.mappers.EntityMappers;
import com.softavail.commsrouter.jpa.JpaDbFacade;
import com.softavail.commsrouter.util.Fields;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Collection;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.persistence.EntityManager;

/**
 * @author ikrustev
 */
public class TaskDispatcher {

  private static final Logger LOGGER = LogManager.getLogger(TaskDispatcher.class);

  private static final Map<String, QueueProcessor> QUEUE_PROCESSORS = Maps.newHashMap();

  private static final Map<String, ScheduledFuture> SCHEDULED_FUTURES = Maps.newHashMap();
  private static final long EVICTION_DELAY_MINUTES = 10;
  private static final boolean DO_NOT_INTERRUPT_IF_RUNNING = false;

  private final JpaDbFacade db;
  private final EntityMappers mappers;
  private final TaskEventHandler taskEventHandler;
  private final ScheduledThreadPoolExecutor threadPool;
  private static Map<String, ScheduledFuture<?>> scheduledWaitTasksTimers = new HashMap<>();

  public TaskDispatcher(JpaDbFacade db, TaskEventHandler taskEventHandler, EntityMappers dtoMappers,
      int threadPoolSize) {

    this.db = db;
    this.mappers = dtoMappers;
    this.taskEventHandler = taskEventHandler;
    this.threadPool = new ScheduledThreadPoolExecutor(threadPoolSize);
    this.threadPool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    startQueueProcessors();
  }

  @SuppressWarnings("unchecked")
  private void startQueueProcessors() {
    try {
      db.transactionManager.executeVoid(em -> db.router.list(em).stream()
          .map(router -> db.queue.list(em, router.getId())).flatMap(Collection::stream)
          .map(Queue::getId).map(this::createQueueProcessor).forEach(QueueProcessor::process));
    } catch (CommsRouterException e) {
      throw new RuntimeException("Can not instantiate TaskDispatcher!", e);
    }
  }

  private synchronized QueueProcessor createQueueProcessor(String queueId) {
    Optional.ofNullable(SCHEDULED_FUTURES.get(queueId))
        .ifPresent(schedule -> schedule.cancel(DO_NOT_INTERRUPT_IF_RUNNING));
    QueueProcessor queueProcessor = QUEUE_PROCESSORS.get(queueId);
    if (queueProcessor == null) {
      queueProcessor = new QueueProcessor(queueId, db, mappers, this, taskEventHandler, threadPool,
          (StateIdleListener) this::handleStateChange);
      QUEUE_PROCESSORS.put(queueId, queueProcessor);
    }
    return queueProcessor;
  }

  private synchronized void removeQueueProcessor(String queueId) {
    QueueProcessor queueProcessor = QUEUE_PROCESSORS.get(queueId);
    if (!queueProcessor.isWorking()) {
      QUEUE_PROCESSORS.remove(queueId);
    }
  }

  private void handleStateChange(String queueId) {
    ScheduledFuture<?> schedule = threadPool.schedule(() -> removeQueueProcessor(queueId),
        EVICTION_DELAY_MINUTES, TimeUnit.MINUTES);
    SCHEDULED_FUTURES.put(queueId, schedule);
  }

  public void close() {
    // @todo: logs and config
    threadPool.shutdown();
    try {
      if (threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
        LOGGER.info("Dispatcher thread pool down.");
      } else {
        LOGGER.warn("Dispatcher thread pool shutdown timeout. Forcing ...");
        threadPool.shutdownNow();
        if (threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
          LOGGER.info("Dispatcher thread pool down after being forced.");
        } else {
          LOGGER.error("Dispatcher thread pool did not shut down.");
        }
      }
    } catch (InterruptedException ex) {
      LOGGER.warn("Interrupted while waiting for the dispatcher thread pool to go shut down.");
    }
  }

  public void dispatchTask(TaskDto taskDto) {
    createQueueProcessor(taskDto.getQueueId()).process();
    startTaskTimer(taskDto);
  }

  public void dispatchAgent(String agentId) {
    try {
      // Get the queueId from the agent
      db.transactionManager.executeVoid(em -> db.agent.get(em, agentId).getQueues().parallelStream()
          .map(ApiObject::getId).map(this::createQueueProcessor).forEach(QueueProcessor::process));
    } catch (CommsRouterException e) {
      LOGGER.error("Dispatch task {}: failure: {}", agentId, e, e);
    }
  }

  public Optional<TaskDto> rejectAssignment(EntityManager em, String taskId)
      throws NotFoundException {

    Task task = db.task.get(em, taskId);
    Agent agent = task.getAgent();

    if (task.getState().isAssigned() && agent != null) {
      agent.setState(AgentState.unavailable);
      task.setState(TaskState.waiting);
      task.setAgent(null);

      return Optional.of(mappers.task.toDto(task));
    }

    return Optional.empty();
  }

  private void startTaskTimer(TaskDto taskDto) {

    LOGGER.debug("Starting wait timer for task {}", taskDto.getId());

    ScheduledFuture<?> timer = threadPool.schedule(() -> {
      onQueuedTaskTimeout(taskDto);
    }, taskDto.getQueuedTimeout(), TimeUnit.SECONDS);

    scheduledWaitTasksTimers.put(taskDto.getId(), timer);
  }

  public void onQueuedTaskTimeout(TaskDto taskDto) {

    try {
      LOGGER.debug("onQueuedTaskTimeout(): Task with ID='{}' timed-out", taskDto.getId());
      scheduledWaitTasksTimers.remove(taskDto.getId());
      processTaskTimeout(taskDto.getId());
    } catch (RuntimeException | CommsRouterException ex) {
      LOGGER.error("Exception while provessing timeout for task {}: {}", taskDto.getId(), ex, ex);
    }
  }

  private void processTaskTimeout(String taskId) throws CommsRouterException {
    TaskDto taskDto;
    taskDto = db.transactionManager.execute((em) -> {
      Task task = db.task.get(em, taskId);
      if (null == task.getState()) {
        return null;
      }

      switch (task.getState()) {
        case completed:
          return null;
        case assigned: {
          if (true) {
            // current tasks timeout logic is used for waiting tasks only!
            return null;
          }
          Agent agent = task.getAgent();
          if (agent != null) {
            if (agent.getState() == AgentState.busy) {
              Fields.update(agent::setState, agent.getState(), AgentState.offline);
            }
          }
          task.setState(TaskState.waiting);
          Fields.update(task::setState, task.getState(), TaskState.waiting);
          Fields.update(task::setAgent, task.getAgent(), null);
        }
          break;
        case waiting: {
          Plan plan = task.getPlan();
          if (plan == null) {
            return null;
          }
          if (plan.getDefaultRoute().getId().equals(task.getRoute().getId())) {
            // default route
            return null;
          }

          Route matchedRoute = null;
          // gethMatchedRoute(task.getRequirements(), plan.getRules(),
          // task.getRoute().getRule().getId(), task.getRoute().getId());
          if (matchedRoute == null) {
            // if not found any other routes in the current rule - don't
            // switch to default plan route for current logic.
            return null;
            // matchedRoute = plan.getDefaultRoute();
          }

          Fields.update(task::setRoute, task.getRoute(), matchedRoute);
          if (matchedRoute.getPriority() != null) {
            Fields.update(task::setPriority, task.getPriority(), matchedRoute.getPriority());
          }
          if (matchedRoute.getTimeout() != null) {
            Fields.update(task::setQueuedTimeout, task.getQueuedTimeout(),
                matchedRoute.getTimeout());
          }
          if (matchedRoute.getQueueId() != null) {
            Queue queue = db.queue.get(em, RouterObjectId.builder().setId(matchedRoute.getQueueId())
                .setRouterId(task.getRouterId()).build());
            Fields.update(task::setQueue, task.getQueue(), queue);
            if (!task.getQueue().getId().equals(matchedRoute.getQueueId())) {
              Fields.update(task::setAgent, task.getAgent(), null);
            }
          }
        }
          break;
        default:
          return null;
      }
      return mappers.task.toDto(task);
    });
    dispatchTask(taskDto);
  }

}
