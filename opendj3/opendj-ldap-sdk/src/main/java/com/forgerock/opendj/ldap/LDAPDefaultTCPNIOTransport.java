/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */

package com.forgerock.opendj.ldap;



import java.io.IOException;

import org.glassfish.grizzly.TransportFactory;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;



/**
 * The default TCPNIOTransport which all LDAPConnectionFactories and
 * LDAPListeners will use unless otherwise specified in their options.
 */
final class LDAPDefaultTCPNIOTransport
{
  private static final TCPNIOTransport DEFAULT_TRANSPORT = TransportFactory
      .getInstance().createTCPTransport();

  static
  {
    try
    {
      DEFAULT_TRANSPORT.start();
    }
    catch (final IOException e)
    {
      throw new RuntimeException(e);
    }
  }



  /**
   * Returns the default TCPNIOTransport which all LDAPConnectionFactories and
   * LDAPListeners will use unless otherwise specified in their options.
   *
   * @return The default TCPNIOTransport.
   */
  public static TCPNIOTransport getInstance()
  {
    return DEFAULT_TRANSPORT;
  }



  private LDAPDefaultTCPNIOTransport()
  {
    // Prevent instantiation.
  }

}