/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.extension.privileged;

import org.mule.test.module.extension.AbstractExtensionFunctionalTestCase;

import org.junit.Test;

public class PrivilegedExecutorTestCase extends AbstractExtensionFunctionalTestCase {

  @Override
  protected String getConfigFile() {
    return "privileged-config.xml";
  }

  @Test
  public void empty() throws Exception {
    flowRunner("empty").run();
  }

  @Test
  public void error() throws Exception {
    flowRunner("error").run();
  }

  @Test
  public void emptyChain() throws Exception {
    flowRunner("emptyChain").run();
  }

  @Test
  public void errorChain() throws Exception {
    flowRunner("errorChain").run();
  }
}
