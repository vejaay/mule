/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.servlet.jetty;

import org.mule.transport.http.functional.HttpFunctionalTestCase;

public class JettyContinuationsHttpFunctionalTestCase extends HttpFunctionalTestCase
{

    @Override
    protected String getConfigResources()
    {
        return "jetty-continuations-http-functional-test.xml";
    }

}