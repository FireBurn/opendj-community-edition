/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2010 Sun Microsystems, Inc.
 */

package org.opends.sdk;



import com.sun.opends.sdk.ldap.InternalConnection;
import com.sun.opends.sdk.util.CompletedFutureResult;



/**
 * A special {@code ConnectionFactory} that can be used to perform internal
 * operations against a {@code ServerConnectionFactory} implementation.
 *
 * @param <C>
 *          The type of client context.
 */
final class InternalConnectionFactory<C> extends AbstractConnectionFactory
{

  private final ServerConnectionFactory<C, Integer> factory;

  private final C clientContext;



  InternalConnectionFactory(final ServerConnectionFactory<C, Integer> factory,
      final C clientContext)
  {
    this.factory = factory;
    this.clientContext = clientContext;
  }



  @Override
  public FutureResult<AsynchronousConnection> getAsynchronousConnection(
      final ResultHandler<AsynchronousConnection> handler)
  {
    final ServerConnection<Integer> serverConnection;
    try
    {
      serverConnection = factory.accept(clientContext);
    }
    catch (final ErrorResultException e)
    {
      if (handler != null)
      {
        handler.handleErrorResult(e);
      }
      return new CompletedFutureResult<AsynchronousConnection>(e);
    }

    final InternalConnection connection = new InternalConnection(
        serverConnection);
    if (handler != null)
    {
      handler.handleResult(connection);
    }
    return new CompletedFutureResult<AsynchronousConnection>(connection);
  }
}