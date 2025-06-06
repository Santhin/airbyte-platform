/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.internal;

import static java.lang.Thread.sleep;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.duration.DurationKt;
import io.airbyte.featureflag.ShouldFailSyncOnDestinationTimeout;
import io.airbyte.metrics.MetricAttribute;
import io.airbyte.metrics.MetricClient;
import io.airbyte.metrics.OssMetricsRegistry;
import io.airbyte.metrics.lib.MetricTags;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks timeouts on {@link io.airbyte.workers.internal.AirbyteDestination#accept} and
 * {@link AirbyteDestination#notifyEndOfInput} calls.
 *
 * Starts monitoring timeouts when calling {@link #runWithTimeoutThread}, which is meant to be
 * running as a background task while calls to
 * {@link io.airbyte.workers.internal.AirbyteDestination#accept} and
 * {@link AirbyteDestination#notifyEndOfInput} are being made.
 *
 * notifyEndOfInput/accept calls timeouts are tracked by calling
 * {@link #startNotifyEndOfInputTimer}, {@link #resetNotifyEndOfInputTimer},
 * {@link #startAcceptTimer} and {@link #resetAcceptTimer}. These methods would be considered as
 * Timed out when either timer goes over {@link #timeout}.
 *
 * The monitor checks for a timeout every {@link #pollInterval}.
 */
@SuppressWarnings("PMD.PreserveStackTrace")
public class DestinationTimeoutMonitor implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(DestinationTimeoutMonitor.class);
  private static final Duration POLL_INTERVAL = Duration.ofMinutes(1);

  private final AtomicLong currentAcceptCallStartTime = new AtomicLong(-1);
  private final AtomicLong currentNotifyEndOfInputCallStartTime = new AtomicLong(-1);
  private final AtomicLong timeSinceLastAction = new AtomicLong(-1);

  private final UUID workspaceId;
  private ExecutorService lazyExecutorService;
  private final UUID connectionId;
  private final MetricClient metricClient;
  private final Duration pollInterval;
  private final Duration timeout;
  private final boolean throwExceptionOnTimeout;

  @VisibleForTesting
  public DestinationTimeoutMonitor(final UUID workspaceId,
                                   final UUID connectionId,
                                   final MetricClient metricClient,
                                   final Duration timeout,
                                   final boolean throwExceptionOnTimeout,
                                   final Duration pollInterval) {
    this.workspaceId = workspaceId;
    this.connectionId = connectionId;
    this.metricClient = metricClient;
    this.timeout = timeout;
    this.throwExceptionOnTimeout = throwExceptionOnTimeout;
    this.pollInterval = pollInterval;
  }

  public DestinationTimeoutMonitor(final UUID workspaceId,
                                   final UUID connectionId,
                                   final MetricClient metricClient,
                                   final Duration timeout,
                                   final boolean throwExceptionOnTimeout) {
    this(workspaceId, connectionId, metricClient, timeout, throwExceptionOnTimeout, POLL_INTERVAL);
  }

  /**
   * Keeps track of two tasks:
   *
   * 1. The given runnableFuture
   *
   * 2. A timeoutMonitorFuture that is created within this method
   *
   * This method completes when either of the above completes.
   *
   * The timeoutMonitorFuture checks if there has been a timeout on either an
   * {@link io.airbyte.workers.internal.AirbyteDestination#accept} call or a
   * {@link AirbyteDestination#notifyEndOfInput} call. If runnableFuture completes before the
   * timeoutMonitorFuture, then the timeoutMonitorFuture will be canceled. If there's a timeout before
   * the runnableFuture completes, then the runnableFuture will be canceled and this method will throw
   * a {@link TimeoutException} (assuming the {@link ShouldFailSyncOnDestinationTimeout} feature flag
   * returned true, otherwise this method will complete without throwing an exception, the runnable
   * won't be canceled and the timeoutMonitorFuture will be canceled).
   *
   * notifyEndOfInput/accept calls timeouts are tracked by calling
   * {@link #startNotifyEndOfInputTimer}, {@link #resetNotifyEndOfInputTimer},
   * {@link #startAcceptTimer} and {@link #resetAcceptTimer}.
   *
   * Note that there are three tasks involved in this method:
   *
   * 1. The given runnableFuture
   *
   * 2. A timeoutMonitorFuture that is created within this method
   *
   * 3. The task that waits for the above two tasks to complete
   *
   */
  public void runWithTimeoutThread(final CompletableFuture<Void> runnableFuture) throws ExecutionException {
    final CompletableFuture<Void> timeoutMonitorFuture = CompletableFuture.runAsync(this::pollForTimeout, getLazyExecutorService());

    try {
      CompletableFuture.anyOf(runnableFuture, timeoutMonitorFuture).get();
    } catch (final InterruptedException e) {
      LOGGER.error("Timeout thread was interrupted.", e);
      return;
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      } else {
        throw e;
      }
    }

    LOGGER.info("thread status... timeout thread: {} , replication thread: {}", timeoutMonitorFuture.isDone(), runnableFuture.isDone());

    if (timeoutMonitorFuture.isDone() && !runnableFuture.isDone()) {
      onTimeout(runnableFuture, timeout.toMillis(), timeSinceLastAction.get());
    }

    timeoutMonitorFuture.cancel(true);
  }

  /**
   * Use to start a timeout timer on a call to
   * {@link io.airbyte.workers.internal.AirbyteDestination#accept}. For each call to
   * {@link #startAcceptTimer} there should be a corresponding call to {@link #resetAcceptTimer} to
   * stop the timeout timer.
   *
   * Only one {@link io.airbyte.workers.internal.AirbyteDestination#accept} call can be tracked at a
   * time. If there's an active {@link io.airbyte.workers.internal.AirbyteDestination#accept} call
   * being tracked and a call to {@link #startAcceptTimer} is done before a call to
   * {@link #resetAcceptTimer}, the timer will start over, ignoring the time of spent on the first
   * {@link io.airbyte.workers.internal.AirbyteDestination#accept} call.
   */
  public void startAcceptTimer() {
    currentAcceptCallStartTime.set(System.currentTimeMillis());
  }

  /**
   * Use to stop the timeout timer on a call to
   * {@link io.airbyte.workers.internal.AirbyteDestination#accept}. Calling this method only makes
   * sense if there's a previous call to {@link #startAcceptTimer}.
   */
  public void resetAcceptTimer() {
    currentAcceptCallStartTime.set(-1);
  }

  /**
   * Use to start a timeout timer on a call to
   * {@link io.airbyte.workers.internal.AirbyteDestination#notifyEndOfInput}. For each call to
   * {@link #startNotifyEndOfInputTimer} there should be a corresponding call to
   * {@link #resetNotifyEndOfInputTimer} to stop the timeout timer.
   *
   * Only one {@link io.airbyte.workers.internal.AirbyteDestination#notifyEndOfInput} call can be
   * tracked at a time. If there's an active
   * {@link io.airbyte.workers.internal.AirbyteDestination#notifyEndOfInput} call being tracked and a
   * call to {@link #startNotifyEndOfInputTimer} is done before a call to
   * {@link #resetNotifyEndOfInputTimer}, the timer will start over, ignoring the time of spent on the
   * first {@link io.airbyte.workers.internal.AirbyteDestination#notifyEndOfInput} call.
   */
  public void startNotifyEndOfInputTimer() {
    currentNotifyEndOfInputCallStartTime.set(System.currentTimeMillis());
  }

  /**
   * Use to stop the timeout timer on a call to
   * {@link io.airbyte.workers.internal.AirbyteDestination#notifyEndOfInput}. Calling this method only
   * makes sense if there's a previous call to {@link #startNotifyEndOfInputTimer}.
   */
  public void resetNotifyEndOfInputTimer() {
    currentNotifyEndOfInputCallStartTime.set(-1);
  }

  private void onTimeout(final CompletableFuture<Void> runnableFuture, final long threshold, final long timeSinceLastAction) {
    if (throwExceptionOnTimeout) {
      runnableFuture.cancel(true);

      throw new TimeoutException(threshold, timeSinceLastAction);
    } else {
      LOGGER.info("Destination has timed out but exception is not thrown due to feature "
          + "flag being disabled for workspace {} and connection {}", workspaceId, connectionId);
    }
  }

  private void pollForTimeout() {
    while (true) {
      try {
        sleep(pollInterval.toMillis());
      } catch (final InterruptedException e) {
        LOGGER.info("Stopping timeout monitor");
        return;
      }

      if (hasTimedOut()) {
        return;
      }
    }
  }

  public boolean hasTimedOut() {
    if (hasTimedOutOnAccept()) {
      return true;
    }
    return hasTimedOutOnNotifyEndOfInput();
  }

  private boolean hasTimedOutOnAccept() {
    final long startTime = currentAcceptCallStartTime.get();

    if (startTime != -1) {
      // by the time we get here, currentAcceptCallStartTime might have already been reset.
      // this won't be a problem since we are not getting the start time from currentAcceptCallStartTime
      // but from startTime
      final var timeSince = System.currentTimeMillis() - startTime;
      if (timeSince > timeout.toMillis()) {
        LOGGER.error("Destination has timed out on accept call");
        metricClient.count(OssMetricsRegistry.WORKER_DESTINATION_ACCEPT_TIMEOUT,
            new MetricAttribute(MetricTags.CONNECTION_ID, connectionId.toString()));
        timeSinceLastAction.set(timeSince);
        return true;
      }
    }
    return false;
  }

  private boolean hasTimedOutOnNotifyEndOfInput() {
    final long startTime = currentNotifyEndOfInputCallStartTime.get();

    if (startTime != -1) {
      // by the time we get here, currentNotifyEndOfInputCallStartTime might have already been reset.
      // this won't be a problem since we are not getting the start time from
      // currentNotifyEndOfInputCallStartTime but from startTime
      final var timeSince = System.currentTimeMillis() - startTime;
      if (timeSince > timeout.toMillis()) {
        LOGGER.error("Destination has timed out on notifyEndOfInput call");
        metricClient.count(OssMetricsRegistry.WORKER_DESTINATION_NOTIFY_END_OF_INPUT_TIMEOUT,
            new MetricAttribute(MetricTags.CONNECTION_ID, connectionId.toString()));
        timeSinceLastAction.set(timeSince);
        return true;
      }
    }

    return false;
  }

  @Override
  public void close() throws Exception {
    if (lazyExecutorService != null) {
      lazyExecutorService.shutdownNow();
      try {
        lazyExecutorService.awaitTermination(10, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static class TimeoutException extends RuntimeException {

    public final String humanReadableThreshold;
    public final String humanReadableTimeSinceLastAction;

    public TimeoutException(final long thresholdMs, final long timeSinceLastActionMs) {
      super(String.format("Last action %s ago, exceeding the threshold of %s.",
          DurationKt.formatMilli(timeSinceLastActionMs),
          DurationKt.formatMilli(thresholdMs)));
      this.humanReadableThreshold = DurationKt.formatMilli(thresholdMs);
      this.humanReadableTimeSinceLastAction = DurationKt.formatMilli(timeSinceLastActionMs);
    }

  }

  /**
   * Return an executor service which is initialized in a lazy way.
   */
  private ExecutorService getLazyExecutorService() {
    if (lazyExecutorService == null) {
      lazyExecutorService = Executors.newFixedThreadPool(1);
    }

    return lazyExecutorService;
  }

  public AtomicLong getTimeSinceLastAction() {
    return timeSinceLastAction;
  }

  public Duration getTimeout() {
    return timeout;
  }

}
