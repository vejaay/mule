/*
 * $Header$
 * $Revision$
 * $Date$
 * ------------------------------------------------------------------------------------------------------
 *
 * Copyright (c) SymphonySoft Limited. All rights reserved.
 * http://www.symphonysoft.com
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.mule.providers.servlet;

import org.mule.MuleManager;
import org.mule.config.i18n.Message;
import org.mule.impl.MuleMessage;
import org.mule.impl.endpoint.MuleEndpointURI;
import org.mule.providers.http.servlet.AbstractReceiverServlet;
import org.mule.providers.service.ConnectorFactory;
import org.mule.umo.UMOMessage;
import org.mule.umo.UMOSession;
import org.mule.umo.endpoint.EndpointException;
import org.mule.umo.endpoint.EndpointNotFoundException;
import org.mule.umo.endpoint.MalformedEndpointException;
import org.mule.umo.endpoint.UMOEndpoint;
import org.mule.umo.provider.NoReceiverForEndpointException;
import org.mule.umo.provider.UMOConnector;
import org.mule.util.MuleObjectHelper;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * <code>MuleRESTReceiverServlet</code> is used for sending a receiving events
 * from the Mule server via a serlet container. The servlet uses the REST style
 * of request processing GET METHOD will do a receive from an external source.
 * you can either specify the transport name i.e. to read from Jms orders.queue
 * http://www.mycompany.com/rest/jms/orders/queue <p/> or a Mule endpoint name
 * to target a specific endpoint config. This would get the first email message
 * recieved by the orderEmailInbox endpoint. <p/>
 * http://www.mycompany.com/rest/ordersEmailInbox <p/> POST Do a sysnchrous call
 * and return a result
 * http://www.clientapplication.com/service/clientquery?custId=1234 <p/> PUT Do
 * an asysnchrous call without returning a result (other than an http status
 * code) http://www.clientapplication.com/service/orders?payload=<order>more
 * beer</order> <p/> DELETE Same as GET only without returning a result
 * 
 * @author <a href="mailto:ross.mason@symphonysoft.com">Ross Mason</a>
 * @version $Revision$
 */

public class MuleRESTReceiverServlet extends AbstractReceiverServlet
{
    private Map receivers;

    public void doInit(ServletConfig servletConfig) throws ServletException
    {
        UMOConnector cnn = null;

        cnn = ConnectorFactory.getConnectorByProtocol("servlet");
        if (cnn == null) {
            throw new ServletException("No servlet connector found using protocol: servlet");
        }
        receivers = ((ServletConnector) cnn).getServletReceivers();
    }

    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws ServletException, IOException
    {
        try {
            UMOEndpoint endpoint = getEndpointForURI(httpServletRequest);
            String timeoutString = httpServletRequest.getParameter("timeout");
            long to = timeout;
            if (timeoutString != null) {
                to = Long.valueOf(timeoutString).longValue();
            }
            if (logger.isDebugEnabled())
                logger.debug("Making request using endpoint: " + endpoint.toString() + " timeout is: " + to);

            UMOMessage returnMessage = endpoint.getConnector().getDispatcher("ANY").receive(endpoint.getEndpointURI(),
                                                                                            to);

            writeResponse(httpServletResponse, returnMessage);
        } catch (Exception e) {
            handleException(e, "Failed to route event through Servlet Receiver", httpServletResponse);
        }
    }

    protected void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws ServletException, IOException
    {
        try {
            ServletMessageReceiver receiver = getReceiverForURI(httpServletRequest);
            httpServletRequest.setAttribute(PAYLOAD_PARAMETER_NAME, payloadParameterName);
            UMOMessage message = new MuleMessage(receiver.getConnector().getMessageAdapter(httpServletRequest));
            UMOMessage returnMessage = receiver.routeMessage(message, true);
            writeResponse(httpServletResponse, returnMessage);

        } catch (Exception e) {
            handleException(e, "Failed to Post event to Mule", httpServletResponse);
        }
    }

    protected void doPut(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws ServletException, IOException
    {
        try {
            ServletMessageReceiver receiver = getReceiverForURI(httpServletRequest);
            httpServletRequest.setAttribute(PAYLOAD_PARAMETER_NAME, payloadParameterName);
            UMOMessage message = new MuleMessage(receiver.getConnector().getMessageAdapter(httpServletRequest));
            receiver.routeMessage(message, MuleManager.getConfiguration().isSynchronous());

            httpServletResponse.setStatus(HttpServletResponse.SC_CREATED);
            if (feedback) {
                httpServletResponse.getWriter().write("Item was created at endpointUri: " + receiver.getEndpointURI());
            }
        } catch (Exception e) {
            handleException(e, "Failed to Post event to Mule" + e.getMessage(), httpServletResponse);
        }
    }

    protected void doDelete(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws ServletException, IOException
    {
        try {
            UMOEndpoint endpoint = getEndpointForURI(httpServletRequest);
            String timeoutString = httpServletRequest.getParameter("timeout");
            long to = timeout;
            if (timeoutString != null) {
                to = Long.valueOf(timeoutString).longValue();
            }
            if (logger.isDebugEnabled())
                logger.debug("Making request using endpoint: " + endpoint.toString() + " timeout is: " + to);

            UMOMessage returnMessage = endpoint.getConnector().getDispatcher("ANY").receive(endpoint.getEndpointURI(),
                                                                                            to);
            if (returnMessage != null) {
                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
            } else {
                httpServletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        } catch (Exception e) {
            handleException(e,
                            "Failed to Delete mule event via receive using uri: " + httpServletRequest.getPathInfo(),
                            httpServletResponse);
        }
    }

    protected UMOEndpoint getEndpointForURI(HttpServletRequest httpServletRequest) throws EndpointException,
            MalformedEndpointException
    {
        String uri = httpServletRequest.getPathInfo();
        if (uri == null) {
            throw new EndpointException(new Message("servlet", 1, httpServletRequest.getRequestURI()));
        }
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        int i = uri.indexOf("/");
        if (i == -1) {
            UMOSession session = MuleManager.getInstance().getModel().getComponentSession(uri);
            if (session == null) {
                throw new EndpointException(new Message("servlet", 2, uri));
            }
        }
        String endpointName = uri.substring(0, i);

        String endpointAddress = httpServletRequest.getParameter("endpoint");
        if (endpointAddress == null && i < uri.length()) {
            endpointAddress = uri.substring(i + 1);
        }
        UMOEndpoint endpoint = MuleManager.getInstance().lookupEndpoint(endpointName);
        if (endpoint == null) {
            endpoint = MuleObjectHelper.getEndpointByProtocol(endpointName);
            if (endpoint == null) {
                throw new EndpointNotFoundException(endpointName);
            }
        }
        if (endpointAddress != null) {
            endpointAddress = endpoint.getEndpointURI().getScheme() + "://" + endpointAddress;
            endpoint.setEndpointURI(new MuleEndpointURI(endpointAddress));
        }
        return endpoint;
    }

    protected ServletMessageReceiver getReceiverForURI(HttpServletRequest httpServletRequest) throws EndpointException
    {
        String uri = httpServletRequest.getPathInfo();
        if (uri == null) {
            throw new EndpointException(new Message("servlet", 1, httpServletRequest.getRequestURI()));
        }
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }

        ServletMessageReceiver receiver = (ServletMessageReceiver) receivers.get(uri);
        if (receiver == null) {
            throw new NoReceiverForEndpointException("No receiver found for endpointUri: " + uri);
        }
        return receiver;
    }
}
