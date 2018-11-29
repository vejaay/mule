/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.http.functional.requester;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mule.module.http.api.HttpHeaders.Names.COOKIE;
import org.mule.api.MuleEvent;

import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

public class HttpRequestCookiesRedirectTestCase extends AbstractHttpRequestTestCase {

  private static final String COOKIE_ROOT_PATH_LOCAL_DOMAIN = "cookieRootPathLocalDomain";
  private static final String COOKIE_EXPIRED = "cookieExpired";
  private static final String COOKIE_TEST_VALUE = "test";

  private static final String CLIENT_COOKIES_ENABLED_FLOW = "clientCookiesEnabled";

  @Override
  protected String getConfigFile()
  {
    return "http-request-cookies-config.xml";
  }

  @Test
  public void cookiesEnabledForSameDomainAndPath() throws Exception
  {
    MuleEvent event = getTestEvent(TEST_MESSAGE);
    event.setFlowVariable("path", "/redirect");

    runFlow(CLIENT_COOKIES_ENABLED_FLOW, event);
    assertCookiesSent(COOKIE_ROOT_PATH_LOCAL_DOMAIN);
  }

  private void assertCookiesSent(String... cookies)
  {
    assertThat(headers.containsKey(COOKIE), is(true));

    Set<String> sentCookies = new HashSet<>(headers.get(COOKIE));
    Set<String> expectedCookies = Sets.newHashSet();

    for (String cookie : cookies)
    {
      expectedCookies.add(cookie + "=" + COOKIE_TEST_VALUE);
    }
    assertThat(sentCookies, equalTo(expectedCookies));
  }

  @Override
  protected void writeResponse(HttpServletResponse response) throws IOException
  {
    super.writeResponse(response);

    if (uri.endsWith("/redirect")) {
      Cookie cookie = new Cookie(COOKIE_ROOT_PATH_LOCAL_DOMAIN, COOKIE_TEST_VALUE);
      cookie.setPath("/");
      cookie.setDomain(".local");
      response.addCookie(cookie);

      cookie = new Cookie(COOKIE_EXPIRED, COOKIE_TEST_VALUE);
      cookie.setPath("/");
      cookie.setDomain(".local");
      cookie.setMaxAge(0);
      response.addCookie(cookie);

      response.setStatus(302);
      response.setHeader("Location", String.format("http://localhost:%s/", httpPort.getValue()));
    }
  }

}
