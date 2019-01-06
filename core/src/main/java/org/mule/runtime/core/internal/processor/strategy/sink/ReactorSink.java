/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy.sink;

import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Sink;

import reactor.core.publisher.FluxSink;

/**
 * Extension of {@link Sink} using Reactor's {@link FluxSink} to accept events.
 */
public interface ReactorSink<E> extends Sink, Disposable {

  E intoSink(CoreEvent event);

  boolean isCancelled();
}
