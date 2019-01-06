/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static java.lang.Long.max;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.BLOCKING;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_INTENSIVE;
import static org.mule.runtime.core.internal.context.thread.notification.ThreadNotificationLogger.THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.subscriberContext;
import static reactor.core.scheduler.Schedulers.fromExecutorService;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.context.thread.notification.ThreadLoggingExecutorServiceDecorator;
import org.mule.runtime.core.internal.processor.strategy.sink.DefaultReactorSink;
import org.mule.runtime.core.internal.processor.strategy.sink.ReactorSink;
import org.mule.runtime.core.internal.processor.strategy.sink.RoundRobinReactorSink;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Creates {@link ReactorProcessingStrategyFactory.ReactorProcessingStrategy} instance that implements the proactor pattern by
 * de-multiplexing incoming events onto a multiple emitter using the {@link SchedulerService#cpuLightScheduler()} to process these
 * events from each emitter. In contrast to the {@link ReactorStreamProcessingStrategy} the proactor pattern treats
 * {@link ProcessingType#CPU_INTENSIVE} and {@link ProcessingType#BLOCKING} processors differently and schedules there execution
 * on dedicated {@link SchedulerService#cpuIntensiveScheduler()} and {@link SchedulerService#ioScheduler()} schedulers.
 * <p/>
 * This processing strategy is not suitable for transactional flows and will fail if used with an active transaction.
 *
 * @since 4.2.0
 */
public class ProactorStreamEmitterProcessingStrategyFactory extends ReactorStreamProcessingStrategyFactory {

  @Override
  public ProcessingStrategy create(MuleContext muleContext, String schedulersNamePrefix) {
    return new ProactorStreamEmitterProcessingStrategy(getBufferSize(),
                                                       getSubscriberCount(),
                                                       getCpuLightSchedulerSupplier(muleContext, schedulersNamePrefix),
                                                       () -> muleContext.getSchedulerService()
                                                           .ioScheduler(muleContext.getSchedulerBaseConfig()
                                                               .withName(schedulersNamePrefix + "." + BLOCKING.name())),
                                                       () -> muleContext.getSchedulerService()
                                                           .cpuIntensiveScheduler(muleContext.getSchedulerBaseConfig()
                                                               .withName(schedulersNamePrefix + "." + CPU_INTENSIVE.name())),
                                                       resolveParallelism(),
                                                       getMaxConcurrency(),
                                                       isMaxConcurrencyEagerCheck(),
                                                       muleContext.getConfiguration().isThreadLoggingEnabled());
  }

  @Override
  protected int getSubscriberCount() {
    return CORES;
  }

  @Override
  protected int resolveParallelism() {
    return Integer.max(CORES, getMaxConcurrency());
  }

  @Override
  public Class<? extends ProcessingStrategy> getProcessingStrategyType() {
    return ProactorStreamEmitterProcessingStrategy.class;
  }

  static class ProactorStreamEmitterProcessingStrategy extends ProactorStreamProcessingStrategy {

    private static Logger LOGGER = getLogger(ProactorStreamEmitterProcessingStrategy.class);

    private final int bufferSize;
    private final boolean isThreadLoggingEnabled;

    private reactor.core.scheduler.Scheduler pipelineScheduler;

    public ProactorStreamEmitterProcessingStrategy(int bufferSize,
                                                   int subscriberCount,
                                                   Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                   Supplier<Scheduler> blockingSchedulerSupplier,
                                                   Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                   int parallelism,
                                                   int maxConcurrency, boolean maxConcurrencyEagerCheck,
                                                   boolean isThreadLoggingEnabled)

    {
      super(subscriberCount, cpuLightSchedulerSupplier,
            blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
            maxConcurrencyEagerCheck);
      this.bufferSize = requireNonNull(bufferSize);
      this.isThreadLoggingEnabled = isThreadLoggingEnabled;
    }

    public ProactorStreamEmitterProcessingStrategy(int bufferSize,
                                                   int subscriberCount,
                                                   Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                   Supplier<Scheduler> blockingSchedulerSupplier,
                                                   Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                   int parallelism,
                                                   int maxConcurrency, boolean maxConcurrencyEagerCheck)

    {
      this(bufferSize, subscriberCount, cpuLightSchedulerSupplier,
           blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
           maxConcurrencyEagerCheck, false);
    }

    @Override
    public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor function) {
      int concurrency = maxConcurrency < subscribers ? maxConcurrency : subscribers;
      final int perEmitterBuffersize = bufferSize / concurrency;
      final long shutdownTimeout = flowConstruct.getMuleContext().getConfiguration().getShutdownTimeout();

      final List<ReactorSink<CoreEvent>> sinks = new ArrayList<>();

      for (int i = 0; i < concurrency; ++i) {
        CountDownLatch completionLatch = new CountDownLatch(1);
        EmitterProcessor<CoreEvent> processor = EmitterProcessor.create(perEmitterBuffersize);
        processor
            .doOnSubscribe(subscription -> currentThread().setContextClassLoader(executionClassloader))
            .transform(function)
            .subscribe(null, e -> completionLatch.countDown(), () -> completionLatch.countDown());

        sinks.add(new DefaultReactorSink<>(processor.sink(), () -> {
          long start = currentTimeMillis();
          try {
            if (!completionLatch.await(max(start - currentTimeMillis() + shutdownTimeout, 0l), MILLISECONDS)) {
              LOGGER.warn("Subscribers of ProcessingStrategy for flow '{}' not completed in {} ms", flowConstruct.getName(),
                          shutdownTimeout);
            }
          } catch (InterruptedException e) {
            currentThread().interrupt();
            throw new MuleRuntimeException(e);
          }
        }, createOnEventConsumer(), perEmitterBuffersize));
      }

      return new ProactorSinkWrapper<>(new RoundRobinReactorSink<>(sinks));
    }

    @Override
    public ReactiveProcessor onPipeline(ReactiveProcessor pipeline) {
      return p -> Flux.from(p).publishOn(pipelineScheduler).transform(super.onPipeline(pipeline));
    }

    @Override
    protected Flux<CoreEvent> scheduleProcessor(ReactiveProcessor processor, Scheduler processorScheduler,
                                                Publisher<CoreEvent> eventPub) {
      return scheduleWithLogging(processor, processorScheduler, eventPub);
    }

    private Flux<CoreEvent> scheduleWithLogging(ReactiveProcessor processor, Scheduler processorScheduler,
                                                Publisher<CoreEvent> eventPub) {
      if (isThreadLoggingEnabled) {
        return from(eventPub)
            .flatMap(e -> subscriberContext()
                .flatMap(ctx -> Mono.just(e).transform(processor)
                    .subscribeOn(fromExecutorService(new ThreadLoggingExecutorServiceDecorator(ctx
                        .getOrEmpty(THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY), decorateScheduler(processorScheduler),
                                                                                               e.getContext().getId())))));
      } else {
        return from(eventPub)
            .transform(processor)
            .subscribeOn(fromExecutorService(decorateScheduler(processorScheduler)));
      }
    }

    @Override
    public void start() throws MuleException {
      super.start();
      pipelineScheduler = fromExecutorService(decorateScheduler(getCpuLightScheduler()));
    }
  }

}
