/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy.sink;

import static org.mule.runtime.core.internal.processor.strategy.AbstractStreamProcessingStrategyFactory.CORES;

import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Sink;

import java.util.function.Consumer;

import reactor.core.publisher.FluxSink;

/**
 * Implementation of {@link Sink} using Reactor's {@link FluxSink} to accept events.
 */
public class DefaultReactorSink<E> implements ReactorSink<E> {

  private final FluxSink<E> fluxSink;
  private final reactor.core.Disposable disposable;
  private final Consumer<CoreEvent> onEventConsumer;
  private final int bufferSize;

  public DefaultReactorSink(FluxSink<E> fluxSink, reactor.core.Disposable disposable,
                            Consumer<CoreEvent> onEventConsumer, int bufferSize) {
    this.fluxSink = fluxSink;
    this.disposable = disposable;
    this.onEventConsumer = onEventConsumer;
    this.bufferSize = bufferSize;
  }

  @Override
  public final void accept(CoreEvent event) {
    onEventConsumer.accept(event);
    fluxSink.next(intoSink(event));
  }

  @Override
  public final boolean emit(CoreEvent event) {
    onEventConsumer.accept(event);
    // Optimization to avoid using synchronized block for all emissions.
    // See: https://github.com/reactor/reactor-core/issues/1037
    long remainingCapacity = fluxSink.requestedFromDownstream();
    if (remainingCapacity == 0) {
      return false;
    } else if (remainingCapacity > (bufferSize > CORES * 4 ? CORES : 0)) {
      // If there is sufficient room in buffer to significantly reduce change of concurrent emission when buffer is full then
      // emit without synchronized block.
      fluxSink.next(intoSink(event));
      return true;
    } else {
      // If there is very little room in buffer also emit but synchronized.
      synchronized (fluxSink) {
        if (remainingCapacity > 0) {
          fluxSink.next(intoSink(event));
          return true;
        } else {
          return false;
        }
      }
    }
  }

  @Override
  public E intoSink(CoreEvent event) {
    return (E) event;
  }

  @Override
  public final void dispose() {
    fluxSink.complete();
    disposable.dispose();
  }
}
