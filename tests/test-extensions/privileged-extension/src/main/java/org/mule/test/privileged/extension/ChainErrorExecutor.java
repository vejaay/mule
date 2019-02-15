/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.privileged.extension;

import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.extension.api.runtime.operation.ComponentExecutor;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;

import org.reactivestreams.Publisher;

import reactor.core.publisher.Mono;

public class ChainErrorExecutor implements ComponentExecutor<OperationModel> {

  @Override
  public Publisher<Object> execute(ExecutionContext<OperationModel> executionContext) {
    return Mono.error(new NullPointerException());
  }

}
