/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Unhandleable.OVERLOAD;
import static org.mule.runtime.core.api.rx.Exceptions.unwrap;
import static org.mule.runtime.core.api.transaction.TransactionCoordination.isTransactionActive;
import static reactor.util.concurrent.Queues.SMALL_BUFFER_SIZE;

import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.processor.strategy.sink.DirectSink;
import org.mule.runtime.core.privileged.event.BaseEventContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Abstract base {@link ProcessingStrategy} that creates a basic {@link Sink} that serializes events.
 */
public abstract class AbstractProcessingStrategy implements ProcessingStrategy {

  public static final String TRANSACTIONAL_ERROR_MESSAGE = "Unable to process a transactional flow asynchronously";

  public static final String PROCESSOR_SCHEDULER_CONTEXT_KEY = "mule.nb.processorScheduler";

  @Override
  public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor pipeline) {
    return new DirectSink(pipeline, createOnEventConsumer(), SMALL_BUFFER_SIZE);
  }

  protected Consumer<CoreEvent> createOnEventConsumer() {
    return event -> {
      if (isTransactionActive()) {
        ((BaseEventContext) event.getContext()).error(new MessagingException(event,
                                                                             new DefaultMuleException(createStaticMessage(TRANSACTIONAL_ERROR_MESSAGE))));
      }
    };
  }

  protected ExecutorService decorateScheduler(Scheduler scheduler) {
    return scheduler;
  }

  /**
   * Checks whether an error indicates that a thread pool is full.
   *
   * @param t the thrown error to analyze
   * @return {@code true} if {@code t} indicates that a thread pool needed for the processing strategy owner rejected a task.
   */
  protected boolean isSchedulerBusy(Throwable t) {
    final Throwable cause = unwrap(t);
    return RejectedExecutionException.class.isAssignableFrom(cause.getClass()) || isOverloadError(cause);
  }

  private boolean isOverloadError(final Throwable cause) {
    if (cause instanceof MessagingException) {
      return ((MessagingException) cause).getEvent().getError()
          .map(e -> e.getErrorType())
          .filter(errorType -> OVERLOAD.getName().equals(errorType.getIdentifier())
              && OVERLOAD.getNamespace().equals(errorType.getNamespace()))
          .isPresent();
    } else {
      return false;
    }
  }
}
