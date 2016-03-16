/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.el.mvel.datatype;

import static org.mule.el.mvel.MessageVariableResolverFactory.FLOW_VARS;
import org.mule.api.MuleEvent;
import org.mule.api.metadata.DataType;

public class FlowVarEnricherDataTypePropagatorTestCase extends AbstractScopedVarAssignmentDataTypePropagatorTestCase
{

    public FlowVarEnricherDataTypePropagatorTestCase()
    {
        super(new FlowVarEnricherDataTypePropagator(), FLOW_VARS);
    }

    @Override
    protected DataType<?> getVariableDataType(MuleEvent event)
    {
        return event.getFlowVariableDataType(PROPERTY_NAME);
    }

    @Override
    protected void setVariable(MuleEvent event, Object propertyValue, DataType dataType)
    {
        event.setFlowVariable(PROPERTY_NAME, propertyValue, dataType);
    }
}