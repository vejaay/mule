/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.routing.filters;

import static org.mule.util.ClassUtils.equal;
import static org.mule.util.ClassUtils.hash;
import org.mule.DefaultMuleEvent;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.context.MuleContextAware;
import org.mule.api.routing.filter.Filter;
import org.mule.construct.Flow;
import org.mule.expression.ExpressionConfig;

import java.text.MessageFormat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Allows boolean expressions to be executed on a message. Note that when using this filter you must be able
 * to either specify a boolean expression when using an expression filter or use one of the standard Mule
 * filters.
 * Otherwise you can use eny expression filter providing you can define a boolean expression i.e. <code>
 * #[xpath:count(/Foo/Bar) == 0]
 * </code> Note that it if the expression is not a boolean expression this filter will return true if the
 * expression returns a result
 */
public class ExpressionFilter implements Filter, MuleContextAware
{
    /**
     * logger used by this class
     */
    protected transient final Log logger = LogFactory.getLog(ExpressionFilter.class);

    private ExpressionConfig config;
    private String fullExpression;
    private boolean nullReturnsTrue = false;
    private MuleContext muleContext;

    /**
     * The class-loader that should be used to load any classes used in scripts. Default to the classloader
     * used to load this filter
     **/
    private ClassLoader expressionEvaluationClassLoader = Thread.currentThread().getContextClassLoader();

    /** For evaluators that are not expression languages we can delegate the execution to another filter */
    private Filter delegateFilter;

    public ExpressionFilter(String expression)
    {
        this.config = new ExpressionConfig();
        this.config.parse(expression);
    }

    public ExpressionFilter()
    {
        super();
        this.config = new ExpressionConfig();
    }

    public void setMuleContext(MuleContext context)
    {
        this.muleContext = context;
    }

    /**
     * Check a given message against this filter.
     * 
     * @param message a non null message to filter.
     * @return <code>true</code> if the message matches the filter
     */
    public boolean accept(MuleMessage message)
    {
        String expr = getFullExpression();
        if (delegateFilter != null)
        {
            boolean result = delegateFilter.accept(message);
            if (logger.isDebugEnabled())
            {
                logger.debug(MessageFormat.format("Result of expression filter: {0} is: {1}", expr, result));
            }
            return result;
        }

        // TODO MULE-9341 Remove Filters. Expression filter will be replaced by something that uses MuleEvent.
        return accept(new DefaultMuleEvent(message, MessageExchangePattern.ONE_WAY, new Flow("", muleContext)));
    }

    /**
     * Check a given event against this filter.
     *
     * @param event a non null event to filter.
     * @return <code>true</code> if the event matches the filter
     */
    @Override
    public boolean accept(MuleEvent event)
    {
        // MULE-4797 Because there is no way to specify the class-loader that script
        // engines use and because scripts when used for expressions are compiled in
        // runtime rather than at initialization the only way to ensure the correct
        // class-loader to used is to switch it out here. We may want to consider
        // passing the class-loader to the ExpressionManager and only doing this for
        // certain ExpressionEvaluators further in.
        ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(expressionEvaluationClassLoader);
            return muleContext.getExpressionManager().evaluateBoolean(getFullExpression(), event, nullReturnsTrue, !nullReturnsTrue);
        }
        finally
        {
            // Restore original context class-loader
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        }
    }

    protected String getFullExpression()
    {
        return config.getExpression();
    }

    public String getExpression()
    {
        return config.getExpression();
    }

    public void setExpression(String expression)
    {
        this.config.setExpression(expression);
        fullExpression = null;
    }

    public boolean isNullReturnsTrue()
    {
        return nullReturnsTrue;
    }

    public void setNullReturnsTrue(boolean nullReturnsTrue)
    {
        this.nullReturnsTrue = nullReturnsTrue;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final ExpressionFilter other = (ExpressionFilter) obj;
        return equal(config, other.config) && equal(delegateFilter, other.delegateFilter)
               && nullReturnsTrue == other.nullReturnsTrue;
    }

    @Override
    public int hashCode()
    {
        return hash(new Object[]{this.getClass(), config, delegateFilter, nullReturnsTrue});
    }
}
