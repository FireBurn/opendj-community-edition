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
 *      Copyright 2009 Sun Microsystems, Inc.
 */
package org.opends.server.replication.server;

import java.util.Iterator;

import org.opends.server.replication.common.ExternalChangeLogSession;
import org.opends.server.replication.common.MultiDomainServerState;
import org.opends.server.replication.protocol.ECLUpdateMsg;
import org.opends.server.replication.protocol.StartECLSessionMsg;
import org.opends.server.types.DirectoryException;
import org.opends.server.util.ServerConstants;

/**
 * This interface defines a session used to search the external changelog
 * in the Directory Server.
 */
public class ExternalChangeLogSessionImpl
  implements ExternalChangeLogSession
{

  ECLServerHandler handler;
  ReplicationServer rs;

  /**
   * Create a new external changelog session.
   * @param rs The replication server to which we will request the log.
   * @throws DirectoryException When an error occurs.
   */
  public ExternalChangeLogSessionImpl(ReplicationServer rs)
  throws DirectoryException
  {
    this.rs = rs;
  }

  /**
   * Create a new external changelog session.
   * @param rs The replication server to which we will request the log.
   * @param startECLSessionMsg The start session message containing the
   *        details of the search request on the ECL.
   * @throws DirectoryException When an error occurs.
   */
  public ExternalChangeLogSessionImpl(
      ReplicationServer rs,
      StartECLSessionMsg startECLSessionMsg)
  throws DirectoryException
  {
    this.rs = rs;

    this.handler = new ECLServerHandler(
        rs.getServerURL(),
        rs.getServerId(),
        rs,
        startECLSessionMsg);
  }

  /**
   * Returns the next message available for the ECL (blocking)
   * null when none.
   * @return the next available message from the ECL.
   * @throws DirectoryException when needed.
   */
  public ECLUpdateMsg getNextUpdate()
  throws DirectoryException
  {
    return handler.getnextUpdate();
  }

  /**
   * Close the session.
   */
  public void close()
  {
    handler.getDomain().stopServer(handler);
  }

  /**
   * Returns the last (newest) cookie value.
   * @return the last cookie value.
   */
  public MultiDomainServerState getLastCookie()
  {
    MultiDomainServerState result = new MultiDomainServerState();
    // Initialize start state for  all running domains with empty state
    Iterator<ReplicationServerDomain> rsdk = this.rs.getCacheIterator();
    if (rsdk != null)
    {
      while (rsdk.hasNext())
      {
        // process a domain
        ReplicationServerDomain rsd = rsdk.next();
        if (rsd.getBaseDn().compareToIgnoreCase(
            ServerConstants.DN_EXTERNAL_CHANGELOG_ROOT)==0)
          continue;
        result.update(rsd.getBaseDn(), rsd.getCLElligibleState());
      }
    }
    return result;
  }
}