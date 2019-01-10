/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.operation;

import static java.lang.String.format;
import static java.util.Collections.singletonMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mule.runtime.api.meta.model.parameter.ParameterGroupModel.DEFAULT_GROUP_NAME;
import static org.mule.runtime.api.util.ComponentLocationProvider.resolveProcessorRepresentation;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.api.rx.Exceptions.checkedFunction;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;
import static org.mule.runtime.core.internal.interception.DefaultInterceptionEvent.INTERCEPTION_COMPONENT;
import static org.mule.runtime.core.internal.interception.DefaultInterceptionEvent.INTERCEPTION_RESOLVED_CONTEXT;
import static org.mule.runtime.core.internal.policy.PolicyNextActionMessageProcessor.POLICY_IS_PROPAGATE_MESSAGE_TRANSFORMATIONS;
import static org.mule.runtime.core.internal.policy.PolicyNextActionMessageProcessor.POLICY_NEXT_OPERATION;
import static org.mule.runtime.core.internal.processor.strategy.AbstractProcessingStrategy.PROCESSOR_SCHEDULER_CONTEXT_KEY;
import static org.mule.runtime.core.internal.util.rx.ImmediateScheduler.IMMEDIATE_SCHEDULER;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processToApply;
import static org.mule.runtime.extension.api.ExtensionConstants.TARGET_PARAMETER_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.TARGET_VALUE_PARAMETER_NAME;
import static org.mule.runtime.module.extension.api.util.MuleExtensionUtils.getInitialiserEvent;
import static org.mule.runtime.module.extension.internal.runtime.operation.ImmutableProcessorChainExecutor.INNER_CHAIN_CTX_MAPPING;
import static org.mule.runtime.module.extension.internal.runtime.resolver.ResolverUtils.resolveValue;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.getMemberField;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.getMemberName;
import static org.mule.runtime.module.extension.internal.util.IntrospectionUtils.isVoid;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getOperationExecutorFactory;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.fromCallable;
import static reactor.core.publisher.Mono.subscriberContext;

import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.api.processor.ContextClassloaderAwareProcessor;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.retry.policy.RetryPolicyTemplate;
import org.mule.runtime.core.api.rx.Exceptions;
import org.mule.runtime.core.api.streaming.CursorProviderFactory;
import org.mule.runtime.core.internal.context.notification.DefaultFlowCallStack;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.policy.OperationExecutionFunction;
import org.mule.runtime.core.internal.policy.OperationPolicy;
import org.mule.runtime.core.internal.policy.PolicyManager;
import org.mule.runtime.core.internal.processor.ParametersResolverProcessor;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.runtime.config.ConfigurationProvider;
import org.mule.runtime.extension.api.runtime.operation.ComponentExecutor;
import org.mule.runtime.extension.api.runtime.operation.ComponentExecutorFactory;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.runtime.extension.api.runtime.operation.Interceptor;
import org.mule.runtime.module.extension.api.loader.java.property.ComponentExecutorModelProperty;
import org.mule.runtime.module.extension.api.runtime.privileged.ExecutionContextAdapter;
import org.mule.runtime.module.extension.internal.loader.ParameterGroupDescriptor;
import org.mule.runtime.module.extension.internal.loader.java.property.FieldOperationParameterModelProperty;
import org.mule.runtime.module.extension.internal.loader.java.property.ParameterGroupModelProperty;
import org.mule.runtime.module.extension.internal.runtime.DefaultExecutionContext;
import org.mule.runtime.module.extension.internal.runtime.ExtensionComponent;
import org.mule.runtime.module.extension.internal.runtime.LazyExecutionContext;
import org.mule.runtime.module.extension.internal.runtime.execution.OperationArgumentResolverFactory;
import org.mule.runtime.module.extension.internal.runtime.objectbuilder.DefaultObjectBuilder;
import org.mule.runtime.module.extension.internal.runtime.objectbuilder.ObjectBuilder;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSet;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolvingContext;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * A {@link Processor} capable of executing extension components.
 * <p>
 * If required, it obtains a configuration instance, evaluate all the operation parameters and executes it by using a
 * {@link #componentExecutor}. This message processor is capable of serving the execution of any {@link } of any
 * {@link ExtensionModel}.
 * <p>
 * A {@link #componentExecutor} is obtained by testing the {@link T} for a {@link ComponentExecutorModelProperty} through which a
 * {@link ComponentExecutorFactory} is obtained. Models with no such property cannot be used with this class. The obtained
 * {@link ComponentExecutor} serve all invocations of {@link #process(CoreEvent)} on {@code this} instance but will not be shared
 * with other instances of {@link ComponentMessageProcessor}. All the {@link Lifecycle} events that {@code this} instance receives
 * will be propagated to the {@link #componentExecutor}.
 * <p>
 * The {@link #componentExecutor} is executed directly but by the means of a {@link DefaultExecutionMediator}
 * <p>
 * Before executing the operation will use the {@link PolicyManager} to lookup for a {@link OperationPolicy} that must be applied
 * to the operation. If there's a policy to be applied then it will interleave the operation execution with the policy logic
 * allowing the policy to execute logic over the operation parameters, change those parameters and then execute logic with the
 * operation response.
 *
 * @since 4.0
 */
public abstract class ComponentMessageProcessor<T extends ComponentModel> extends ExtensionComponent<T>
    implements ContextClassloaderAwareProcessor, ParametersResolverProcessor<T>, Lifecycle {

  private static final Logger LOGGER = getLogger(ComponentMessageProcessor.class);

  static final String INVALID_TARGET_MESSAGE =
      "Root component '%s' defines an invalid usage of operation '%s' which uses %s as %s";

  protected final ExtensionModel extensionModel;
  protected final ResolverSet resolverSet;
  protected final String target;
  protected final String targetValue;
  protected final RetryPolicyTemplate retryPolicyTemplate;

  private final ReflectionCache reflectionCache;

  protected ExecutionMediator executionMediator;
  protected ComponentExecutor componentExecutor;
  protected ReturnDelegate returnDelegate;
  protected PolicyManager policyManager;

  private String resolvedProcessorRepresentation;
  private boolean initialised = false;

  public ComponentMessageProcessor(ExtensionModel extensionModel,
                                   T componentModel,
                                   ConfigurationProvider configurationProvider,
                                   String target,
                                   String targetValue,
                                   ResolverSet resolverSet,
                                   CursorProviderFactory cursorProviderFactory,
                                   RetryPolicyTemplate retryPolicyTemplate,
                                   ExtensionManager extensionManager,
                                   PolicyManager policyManager,
                                   ReflectionCache reflectionCache) {
    super(extensionModel, componentModel, configurationProvider, cursorProviderFactory, extensionManager);
    this.extensionModel = extensionModel;
    this.resolverSet = resolverSet;
    this.target = target;
    this.targetValue = targetValue;
    this.policyManager = policyManager;
    this.retryPolicyTemplate = retryPolicyTemplate;
    this.reflectionCache = reflectionCache;
  }

  @Override
  public CoreEvent process(CoreEvent event) throws MuleException {
    return processToApply(event, this);
  }

  @Override
  public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
    return from(publisher)
        .flatMap(event -> subscriberContext()
            .map(ctx -> addContextToEvent(event, ctx)))
        .flatMap(checkedFunction(event -> {
          final Optional<ConfigurationInstance> configuration = resolveConfiguration(event);
          final Map<String, Object> resolutionResult = getResolutionResult(event, configuration);
          final PrecalculatedExecutionContextAdapter<T> precalculatedContext = getPrecalculatedContext(event);
          final Scheduler currentScheduler = ((InternalEvent) event).getInternalParameter(PROCESSOR_SCHEDULER_CONTEXT_KEY);

          OperationExecutionFunction operationExecutionFunction;

          if (getLocation() != null && isInterceptedComponent(getLocation(), (InternalEvent) event)
              && precalculatedContext != null) {
            ExecutionContextAdapter<T> operationContext = getPrecalculatedContext(event);

            operationExecutionFunction = (parameters, operationEvent) -> {
              operationContext.setCurrentScheduler(currentScheduler != null ? currentScheduler : IMMEDIATE_SCHEDULER);
              return doProcessWithErrorMapping(operationEvent, operationContext);
            };
          } else {
            operationExecutionFunction = (parameters, operationEvent) -> {
              ExecutionContextAdapter<T> operationContext;
              try {
                operationContext = createExecutionContext(configuration, parameters, operationEvent,
                                                          currentScheduler != null ? currentScheduler : IMMEDIATE_SCHEDULER);
              } catch (MuleException e) {
                return error(e);
              }
              return doProcessWithErrorMapping(operationEvent, operationContext);
            };
          }

          if (getLocation() != null) {
            ((DefaultFlowCallStack) event.getFlowCallStack())
                .setCurrentProcessorPath(resolvedProcessorRepresentation);
            return Mono.from(policyManager
                .createOperationPolicy(this, event, () -> resolutionResult)
                .process(event, operationExecutionFunction, () -> resolutionResult, getLocation()));
          } else {
            // If this operation has no component location then it is internal. Don't apply policies on internal operations.
            return Mono.from(operationExecutionFunction.execute(resolutionResult, event));
          }
        }));
  }

  private CoreEvent addContextToEvent(CoreEvent event, Context ctx) {
    final Optional<Scheduler> currentScheduler = ctx.getOrEmpty(PROCESSOR_SCHEDULER_CONTEXT_KEY);

    if (ctx.hasKey(POLICY_NEXT_OPERATION) || ctx.hasKey(POLICY_IS_PROPAGATE_MESSAGE_TRANSFORMATIONS)) {
      Function<Context, Context> context = innerChainCtx -> {
        if (ctx.hasKey(POLICY_NEXT_OPERATION)) {
          innerChainCtx = innerChainCtx.put(POLICY_NEXT_OPERATION, ctx.get(POLICY_NEXT_OPERATION));
        }
        if (ctx.hasKey(POLICY_IS_PROPAGATE_MESSAGE_TRANSFORMATIONS)) {
          innerChainCtx = innerChainCtx.put(POLICY_IS_PROPAGATE_MESSAGE_TRANSFORMATIONS,
                                            ctx.get(POLICY_IS_PROPAGATE_MESSAGE_TRANSFORMATIONS));
        }
        return innerChainCtx;
      };
      return quickCopy(event, ImmutableMap.<String, Object>builder()
          .put(INNER_CHAIN_CTX_MAPPING, context)
          .put(PROCESSOR_SCHEDULER_CONTEXT_KEY, currentScheduler.orElse(IMMEDIATE_SCHEDULER))
          .build());

    } else if (currentScheduler.isPresent()) {
      return quickCopy(event, singletonMap(PROCESSOR_SCHEDULER_CONTEXT_KEY, currentScheduler.get()));
    } else {
      return event;
    }
  }

  private Optional<ConfigurationInstance> resolveConfiguration(CoreEvent event) {
    if (getLocation() != null && getPrecalculatedContext(event) != null) {
      // If the event already contains an execution context, use that one.
      // Only for interceptable components!
      return getPrecalculatedContext(event).getConfiguration();
    } else {
      // Otherwise, generate the context as usual.
      return getConfiguration(event);
    }
  }

  /**
   * While a hook in reactor is used to map Throwable to MessagingException when an error occurs this does not cover the case
   * where an error is explicitly triggered via a Sink such as such as when using Mono.create in ReactorCompletionCallback rather
   * than being thrown by a reactor operator. Although changes could be made to Mule to cater for this in
   * AbstractMessageProcessorChain, this is not trivial given processor interceptors and a potent performance overhead associated
   * with the addition of many additional flatMaps. It would be slightly clearer to create the MessagingException in
   * ReactorCompletionCallback where Mono.error is used but we don't have a reference to the processor there.
   */
  private Publisher<CoreEvent> doProcessWithErrorMapping(CoreEvent operationEvent, ExecutionContextAdapter<T> operationContext) {
    return doProcess(operationEvent, operationContext)
        .onErrorMap(e -> !(e instanceof MessagingException), e -> new MessagingException(operationEvent, e, this));
  }

  private PrecalculatedExecutionContextAdapter<T> getPrecalculatedContext(CoreEvent event) {
    return ((InternalEvent) event).getInternalParameter(INTERCEPTION_RESOLVED_CONTEXT);
  }

  protected Mono<CoreEvent> doProcess(CoreEvent event, ExecutionContextAdapter<T> operationContext) {
    return executeOperation(operationContext)
        .map(value -> asReturnValue(operationContext, value))
        .switchIfEmpty(fromCallable(() -> asReturnValue(operationContext, null)))
        .onErrorMap(Exceptions::unwrap);
  }

  private CoreEvent asReturnValue(ExecutionContextAdapter<T> operationContext, Object value) {
    if (value instanceof CoreEvent) {
      return (CoreEvent) value;
    } else {
      return returnDelegate.asReturnValue(value, operationContext);
    }
  }

  private Mono<Object> executeOperation(ExecutionContextAdapter operationContext) {
    return Mono.from(executionMediator.execute(componentExecutor, operationContext));
  }

  private ExecutionContextAdapter<T> createExecutionContext(Optional<ConfigurationInstance> configuration,
                                                            Map<String, Object> resolvedParameters,
                                                            CoreEvent event, Scheduler currentScheduler)
      throws MuleException {

    return new DefaultExecutionContext<>(extensionModel, configuration, resolvedParameters, componentModel, event,
                                         getCursorProviderFactory(), streamingManager, this, retryPolicyTemplate,
                                         currentScheduler, muleContext);
  }

  @Override
  protected void doInitialise() throws InitialisationException {
    if (!initialised) {
      returnDelegate = createReturnDelegate();
      initialiseIfNeeded(resolverSet, muleContext);
      componentExecutor = createComponentExecutor();
      executionMediator = createExecutionMediator();
      initialiseIfNeeded(componentExecutor, true, muleContext);

      if (getLocation() != null) {
        resolvedProcessorRepresentation =
            resolveProcessorRepresentation(muleContext.getConfiguration().getId(), getLocation().getLocation(), this);
      }

      initialised = true;
    }
  }

  private ComponentExecutor<T> createComponentExecutor() {
    Map<String, Object> params = new HashMap<>();

    LazyValue<Optional<ConfigurationInstance>> staticConfiguration = new LazyValue<>(this::getStaticConfiguration);
    LazyValue<ValueResolvingContext> resolvingContext =
        new LazyValue<>(() -> {
          CoreEvent initialiserEvent = null;
          try {
            initialiserEvent = getInitialiserEvent();
            return ValueResolvingContext.builder(initialiserEvent, expressionManager)
                .withConfig(staticConfiguration.get())
                .build();
          } finally {
            if (initialiserEvent != null) {
              ((BaseEventContext) initialiserEvent.getContext()).success();
            }
          }
        });

    componentModel.getParameterGroupModels().stream().forEach(group -> {
      if (group.getName().equals(DEFAULT_GROUP_NAME)) {
        group.getParameterModels().stream()
            .filter(p -> p.getModelProperty(FieldOperationParameterModelProperty.class).isPresent())
            .forEach(p -> {
              ValueResolver<?> resolver = resolverSet.getResolvers().get(p.getName());
              if (resolver != null) {
                try {
                  params.put(getMemberName(p), resolveValue(resolver, resolvingContext.get()));
                } catch (MuleException e) {
                  throw new MuleRuntimeException(e);
                } finally {
                  resolvingContext.get().close();
                }
              }
            });
      } else {
        ParameterGroupDescriptor groupDescriptor = group.getModelProperty(ParameterGroupModelProperty.class)
            .map(g -> g.getDescriptor())
            .orElse(null);

        if (groupDescriptor == null) {
          return;
        }

        List<ParameterModel> fieldParameters = getGroupsOfFieldParameters(group);

        if (fieldParameters.isEmpty()) {
          return;
        }

        ObjectBuilder groupBuilder = createFieldParameterGroupBuilder(groupDescriptor, fieldParameters);

        try {
          params.put(((Field) groupDescriptor.getContainer()).getName(), groupBuilder.build(resolvingContext.get()));
        } catch (MuleException e) {
          throw new MuleRuntimeException(e);
        } finally {
          resolvingContext.get().close();
        }
      }
    });

    return getOperationExecutorFactory(componentModel).createExecutor(componentModel, params);
  }

  private ObjectBuilder createFieldParameterGroupBuilder(ParameterGroupDescriptor groupDescriptor,
                                                         List<ParameterModel> fieldParameters) {
    DefaultObjectBuilder groupBuilder =
        new DefaultObjectBuilder(groupDescriptor.getType().getDeclaringClass().get(), reflectionCache);

    fieldParameters.forEach(p -> {
      ValueResolver resolver = resolverSet.getResolvers().get(p.getName());
      if (resolver != null) {
        Optional<Field> memberField = getMemberField(p);
        if (memberField.isPresent()) {
          groupBuilder.addPropertyResolver(getMemberField(p).get(), resolver);
        } else {
          groupBuilder.addPropertyResolver(p.getName(), resolver);
        }
      }
    });
    return groupBuilder;
  }

  private List<ParameterModel> getGroupsOfFieldParameters(ParameterGroupModel group) {
    return group.getParameterModels().stream()
        .filter(p -> p.getModelProperty(FieldOperationParameterModelProperty.class).isPresent())
        .collect(toList());
  }

  protected ReturnDelegate createReturnDelegate() {
    if (isVoid(componentModel)) {
      return VoidReturnDelegate.INSTANCE;
    }

    return !isTargetPresent()
        ? getValueReturnDelegate()
        : getTargetReturnDelegate();
  }


  protected TargetReturnDelegate getTargetReturnDelegate() {
    return new TargetReturnDelegate(target, targetValue, componentModel, expressionManager, cursorProviderFactory, muleContext);
  }

  protected ValueReturnDelegate getValueReturnDelegate() {
    return new ValueReturnDelegate(componentModel, cursorProviderFactory, muleContext);
  }

  protected boolean isTargetPresent() {
    if (isBlank(target)) {
      return false;
    }

    if (muleContext.getExpressionManager().isExpression(target)) {
      throw new IllegalOperationException(format(INVALID_TARGET_MESSAGE, getLocation().getRootContainerName(),
                                                 componentModel.getName(),
                                                 "an expression", TARGET_PARAMETER_NAME));
    } else if (!muleContext.getExpressionManager().isExpression(targetValue)) {
      throw new IllegalOperationException(format(INVALID_TARGET_MESSAGE, getLocation().getRootContainerName(),
                                                 componentModel.getName(), "something that is not an expression",
                                                 TARGET_VALUE_PARAMETER_NAME));
    }

    return true;
  }

  protected Optional<String> getTarget() {
    return isTargetPresent() ? of(target) : empty();
  }

  @Override
  public void doStart() throws MuleException {
    startIfNeeded(componentExecutor);
  }

  @Override
  public void doStop() throws MuleException {
    stopIfNeeded(componentExecutor);
  }

  @Override
  public void doDispose() {
    disposeIfNeeded(componentExecutor, LOGGER);
    initialised = false;
  }

  protected ExecutionMediator createExecutionMediator() {
    return new DefaultExecutionMediator(extensionModel, componentModel, connectionManager, muleContext.getErrorTypeRepository());
  }

  /**
   * Validates that the {@link #componentModel} is valid for the given {@code configurationProvider}
   *
   * @throws IllegalOperationException If the validation fails
   */
  @Override
  protected abstract void validateOperationConfiguration(ConfigurationProvider configurationProvider);

  @Override
  protected ParameterValueResolver getParameterValueResolver() {
    CoreEvent event = getInitialiserEvent(muleContext);
    try (ValueResolvingContext ctx = ValueResolvingContext.builder(event, expressionManager).build()) {
      LazyExecutionContext executionContext = new LazyExecutionContext<>(resolverSet, componentModel, extensionModel, ctx);
      return new OperationParameterValueResolver(executionContext, resolverSet, reflectionCache, expressionManager);
    } finally {
      if (event != null) {
        ((BaseEventContext) event.getContext()).success();
      }
    }
  }

  @Override
  public abstract ProcessingType getProcessingType();

  @Override
  public void resolveParameters(CoreEvent.Builder eventBuilder,
                                BiConsumer<Map<String, Supplier<Object>>, ExecutionContext> afterConfigurer)
      throws MuleException {
    if (componentExecutor instanceof OperationArgumentResolverFactory) {
      ExecutionContextAdapter<T> delegateExecutionContext = createExecutionContext(eventBuilder.build());
      PrecalculatedExecutionContextAdapter executionContext = new PrecalculatedExecutionContextAdapter(delegateExecutionContext,
                                                                                                       componentExecutor);

      final DefaultExecutionMediator mediator = (DefaultExecutionMediator) executionMediator;
      List<Interceptor> interceptors = mediator.collectInterceptors(executionContext.getConfiguration(),
                                                                    executionContext.getOperationExecutor());
      InterceptorsExecutionResult beforeExecutionResult = mediator.before(executionContext, interceptors);
      if (beforeExecutionResult.isOk()) {
        final Map<String, Supplier<Object>> resolvedArguments = ((OperationArgumentResolverFactory<T>) componentExecutor)
            .createArgumentResolver(componentModel)
            .apply(executionContext);
        afterConfigurer.accept(resolvedArguments, executionContext);
        executionContext.changeEvent(eventBuilder.build());
      } else {
        disposeResolvedParameters(executionContext, interceptors);
        throw new DefaultMuleException("Interception execution for operation not ok", beforeExecutionResult.getThrowable());
      }
    }
  }

  @Override
  public void disposeResolvedParameters(ExecutionContext<T> executionContext) {
    final DefaultExecutionMediator mediator = (DefaultExecutionMediator) executionMediator;
    List<Interceptor> interceptors = mediator.collectInterceptors(executionContext.getConfiguration(),
                                                                  executionContext instanceof PrecalculatedExecutionContextAdapter
                                                                      ? ((PrecalculatedExecutionContextAdapter) executionContext)
                                                                          .getOperationExecutor()
                                                                      : componentExecutor);

    disposeResolvedParameters(executionContext, interceptors);
  }

  private void disposeResolvedParameters(ExecutionContext<T> executionContext, List<Interceptor> interceptors) {
    final DefaultExecutionMediator mediator = (DefaultExecutionMediator) executionMediator;

    mediator.after(executionContext, null, interceptors);
  }

  private ExecutionContextAdapter<T> createExecutionContext(CoreEvent event) throws MuleException {
    Optional<ConfigurationInstance> configuration = getConfiguration(event);
    return createExecutionContext(configuration, getResolutionResult(event, configuration), event, IMMEDIATE_SCHEDULER);
  }

  private Map<String, Object> getResolutionResult(CoreEvent event, Optional<ConfigurationInstance> configuration)
      throws MuleException {
    try (ValueResolvingContext context = ValueResolvingContext.builder(event, expressionManager)
        .withConfig(configuration).build()) {
      return resolverSet.resolve(context).asMap();
    }
  }

  private boolean isInterceptedComponent(ComponentLocation location, InternalEvent event) {
    final Component component = event.getInternalParameter(INTERCEPTION_COMPONENT);
    if (component != null) {
      return location.equals(component.getLocation());
    }
    return false;
  }
}
