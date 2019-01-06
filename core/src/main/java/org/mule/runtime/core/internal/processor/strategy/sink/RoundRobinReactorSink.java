/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy.sink;

import org.mule.runtime.core.api.event.CoreEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

public class RoundRobinReactorSink<E> implements ReactorSink<E> {

  private final List<ReactorSink<E>> fluxSinks;
  private final AtomicInteger index = new AtomicInteger(0);
  // Saving update function to avoid creating the lambda every time
  private final IntUnaryOperator update;

  public RoundRobinReactorSink(List<ReactorSink<E>> sinks) {
    this.fluxSinks = sinks;
    this.update = (value) -> (value + 1) % fluxSinks.size();
  }

  @Override
  public void dispose() {
    fluxSinks.stream().forEach(sink -> sink.dispose());
  }

  @Override
  public void accept(CoreEvent event) {
    fluxSinks.get(nextIndex()).accept(event);
  }

  private int nextIndex() {
    return index.getAndUpdate(update);
  }

  @Override
  public boolean emit(CoreEvent event) {
    return fluxSinks.get(nextIndex()).emit(event);
  }

  @Override
  public E intoSink(CoreEvent event) {
    return (E) event;
  }

}
