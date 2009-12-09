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
 *      Copyright 2006-2009 Sun Microsystems, Inc.
 */
package org.opends.server.replication.service;

import static org.opends.messages.ReplicationMessages.*;
import static org.opends.server.loggers.ErrorLogger.logError;
import static org.opends.server.loggers.debug.DebugLogger.getTracer;
import static org.opends.server.util.StaticUtils.stackTraceToSingleLineString;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.opends.messages.Category;
import org.opends.messages.Message;
import org.opends.messages.MessageBuilder;
import org.opends.messages.Severity;
import org.opends.server.api.DirectoryThread;
import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.replication.common.ChangeNumber;
import org.opends.server.replication.common.DSInfo;
import org.opends.server.replication.common.MutableBoolean;
import org.opends.server.replication.common.RSInfo;
import org.opends.server.replication.common.ServerState;
import org.opends.server.replication.common.ServerStatus;
import org.opends.server.replication.protocol.ChangeStatusMsg;
import org.opends.server.replication.protocol.HeartbeatMonitor;
import org.opends.server.replication.protocol.MonitorMsg;
import org.opends.server.replication.protocol.MonitorRequestMsg;
import org.opends.server.replication.protocol.ProtocolSession;
import org.opends.server.replication.protocol.ProtocolVersion;
import org.opends.server.replication.protocol.ReplServerStartDSMsg;
import org.opends.server.replication.protocol.ReplServerStartMsg;
import org.opends.server.replication.protocol.ReplSessionSecurity;
import org.opends.server.replication.protocol.ReplicationMsg;
import org.opends.server.replication.protocol.ServerStartECLMsg;
import org.opends.server.replication.protocol.ServerStartMsg;
import org.opends.server.replication.protocol.StartECLSessionMsg;
import org.opends.server.replication.protocol.StartSessionMsg;
import org.opends.server.replication.protocol.StopMsg;
import org.opends.server.replication.protocol.TopologyMsg;
import org.opends.server.replication.protocol.UpdateMsg;
import org.opends.server.replication.protocol.WindowMsg;
import org.opends.server.replication.protocol.WindowProbeMsg;
import org.opends.server.util.ServerConstants;
import org.opends.server.replication.server.ReplicationServer;

/**
 * The broker for Multi-master Replication.
 */
public class ReplicationBroker
{

  /**
   * The tracer object for the debug logger.
   */
  private static final DebugTracer TRACER = getTracer();
  private boolean shutdown = false;
  private Collection<String> servers;
  private boolean connected = false;
  private String replicationServer = "Not connected";
  private ProtocolSession session = null;
  private final ServerState state;
  private final String baseDn;
  private final int serverId;
  private Semaphore sendWindow;
  private int maxSendWindow;
  private int rcvWindow = 100;
  private int halfRcvWindow = rcvWindow/2;
  private int maxRcvWindow = rcvWindow;
  private int timeout = 0;
  private short protocolVersion;
  private ReplSessionSecurity replSessionSecurity;
  // My group id
  private byte groupId = (byte) -1;
  // The group id of the RS we are connected to
  private byte rsGroupId = (byte) -1;
  // The server id of the RS we are connected to
  private Integer rsServerId = -1;
  // The server URL of the RS we are connected to
  private String rsServerUrl = null;
  // Our replication domain
  private ReplicationDomain domain = null;

  /**
   * This object is used as a conditional event to be notified about
   * the reception of monitor information from the Replication Server.
   */
  private final MutableBoolean monitorResponse = new MutableBoolean(false);

  /**
   * A Map containing the ServerStates of all the replicas in the topology
   * as seen by the ReplicationServer the last time it was polled or the last
   * time it published monitoring information.
   */
  private HashMap<Integer, ServerState> replicaStates =
    new HashMap<Integer, ServerState>();

  /**
   * A Map containing the ServerStates of all the replication servers in the
   * topology as seen by the ReplicationServer the last time it was polled or
   * the last time it published monitoring information.
   */
  private HashMap<Integer, ServerState> rsStates =
    new HashMap<Integer, ServerState>();

  /**
   * The expected duration in milliseconds between heartbeats received
   * from the replication server.  Zero means heartbeats are off.
   */
  private long heartbeatInterval = 0;
  /**
   * A thread to monitor heartbeats on the session.
   */
  private HeartbeatMonitor heartbeatMonitor = null;
  /**
   * The number of times the connection was lost.
   */
  private int numLostConnections = 0;
  /**
   * When the broker cannot connect to any replication server
   * it log an error and keeps continuing every second.
   * This boolean is set when the first failure happens and is used
   * to avoid repeating the error message for further failure to connect
   * and to know that it is necessary to print a new message when the broker
   * finally succeed to connect.
   */
  private boolean connectionError = false;
  private final Object connectPhaseLock = new Object();

  // Same group id poller thread
  private SameGroupIdPoller sameGroupIdPoller = null;

  /**
   * The thread that publishes messages to the RS containing the current
   * change time of this DS.
   */
  private CTHeartbeatPublisherThread ctHeartbeatPublisherThread = null;
  /**
   * The expected period in milliseconds between these messages are sent
   * to the replication server.  Zero means heartbeats are off.
   */
  private long changeTimeHeartbeatSendInterval = 0;
  /*
   * Properties for the last topology info received from the network.
   */
  // Info for other DSs.
  // Warning: does not contain info for us (for our server id)
  private List<DSInfo> dsList = new ArrayList<DSInfo>();
  // Info for other RSs.
  private List<RSInfo> rsList = new ArrayList<RSInfo>();

  private long generationID;
  private int updateDoneCount = 0;
  private boolean connectRequiresRecovery = false;

  /**
   * Creates a new ReplicationServer Broker for a particular ReplicationDomain.
   *
   * @param replicationDomain The replication domain that is creating us.
   * @param state The ServerState that should be used by this broker
   *        when negotiating the session with the replicationServer.
   * @param baseDn The base DN that should be used by this broker
   *        when negotiating the session with the replicationServer.
   * @param serverID2 The server ID that should be used by this broker
   *        when negotiating the session with the replicationServer.
   * @param window The size of the send and receive window to use.
   * @param generationId The generationId for the server associated to the
   * provided serverId and for the domain associated to the provided baseDN.
   * @param heartbeatInterval The interval (in ms) between heartbeats requested
   *        from the replicationServer, or zero if no heartbeats are requested.
   * @param replSessionSecurity The session security configuration.
   * @param groupId The group id of our domain.
   * @param changeTimeHeartbeatInterval The interval (in ms) between Change
   *        time  heartbeats are sent to the RS,
   *        or zero if no CN heartbeat should be sent.
   */
  public ReplicationBroker(ReplicationDomain replicationDomain,
    ServerState state, String baseDn, int serverID2, int window,
    long generationId, long heartbeatInterval,
    ReplSessionSecurity replSessionSecurity, byte groupId,
    long changeTimeHeartbeatInterval)
  {
    this.domain = replicationDomain;
    this.baseDn = baseDn;
    this.serverId = serverID2;
    this.state = state;
    this.protocolVersion = ProtocolVersion.getCurrentVersion();
    this.replSessionSecurity = replSessionSecurity;
    this.groupId = groupId;
    this.generationID = generationId;
    this.heartbeatInterval = heartbeatInterval;
    this.maxRcvWindow = window;
    this.maxRcvWindow = window;
    this.halfRcvWindow = window /2;
    this.changeTimeHeartbeatSendInterval = changeTimeHeartbeatInterval;
  }

  /**
   * Start the ReplicationBroker.
   */
  public void start()
  {
    shutdown = false;
    this.rcvWindow = this.maxRcvWindow;
    this.connect();
  }

  /**
   * Start the ReplicationBroker.
   *
   * @param servers list of servers used
   */
  public void start(Collection<String> servers)
  {
    /*
     * Open Socket to the ReplicationServer
     * Send the Start message
     */
    shutdown = false;
    this.servers = servers;

    if (servers.size() < 1)
    {
      Message message = NOTE_NEED_MORE_THAN_ONE_CHANGELOG_SERVER.get();
      logError(message);
    }

    this.rcvWindow = this.maxRcvWindow;
    this.connect();
  }

  /**
   * Gets the group id of the RS we are connected to.
   * @return The group id of the RS we are connected to
   */
  public byte getRsGroupId()
  {
    return rsGroupId;
  }

  /**
   * Gets the server id of the RS we are connected to.
   * @return The server id of the RS we are connected to
   */
  public Integer getRsServerId()
  {
    return rsServerId;
  }

 /**
   * Gets the server id.
   * @return The server id
   */
  public int getServerId()
  {
    return serverId;
  }

  /**
   * Gets the server id.
   * @return The server id
   */
  private long getGenerationID()
  {
    if (domain != null)
      return domain.getGenerationID();
    else
      return generationID;
  }

  /**
   * Gets the server url of the RS we are connected to.
   * @return The server url of the RS we are connected to
   */
  public String getRsServerUrl()
  {
    return rsServerUrl;
  }

  /**
   * Bag class for keeping info we get from a server in order to compute the
   * best one to connect to. This is in fact a wrapper to a
   * ReplServerStartMsg (V3) or a ReplServerStartDSMsg (V4).
   */
  public static class ServerInfo
  {
    private short protocolVersion;
    private long generationId;
    private byte groupId = (byte) -1;
    private int serverId;
    private String serverURL;
    private String baseDn = null;
    private int windowSize;
    private ServerState serverState;
    private boolean sslEncryption;
    private int degradedStatusThreshold = -1;
    // Keeps the -1 value if created with a ReplServerStartMsg
    private int weight = -1;
    // Keeps the -1 value if created with a ReplServerStartMsg
    private int connectedDSNumber = -1;

    /**
     * Create a new instance of ServerInfo wrapping the passed message.
     * @param msg Message to wrap.
     * @return The new instance wrapping the passed message.
     * @throws IllegalArgumentException If the passed message has an unexpected
     *                                  type.
     */
    public static ServerInfo newServerInfo(
      ReplicationMsg msg) throws IllegalArgumentException
    {
      if (msg instanceof ReplServerStartMsg)
      {
        // This is a ReplServerStartMsg (RS uses protocol V3 or under)
        ReplServerStartMsg replServerStartMsg = (ReplServerStartMsg)msg;
        return new ServerInfo(replServerStartMsg);
      }
      else if (msg instanceof ReplServerStartDSMsg)
      {
        // This is a ReplServerStartDSMsg (RS uses protocol V4 or higher)
        ReplServerStartDSMsg replServerStartDSMsg = (ReplServerStartDSMsg)msg;
        return new ServerInfo(replServerStartDSMsg);
      }

      // Unsupported message type: should not happen
      throw new IllegalArgumentException("Unexpected PDU type: " +
        msg.getClass().getName() + " :\n" + msg.toString());
    }

    /**
     * Constructs a ServerInfo object wrapping a ReplServerStartMsg.
     * @param replServerStartMsg The ReplServerStartMsg this object will wrap.
     */
    private ServerInfo(ReplServerStartMsg replServerStartMsg)
    {
      this.protocolVersion = replServerStartMsg.getVersion();
      this.generationId = replServerStartMsg.getGenerationId();
      this.groupId = replServerStartMsg.getGroupId();
      this.serverId = replServerStartMsg.getServerId();
      this.serverURL = replServerStartMsg.getServerURL();
      this.baseDn = replServerStartMsg.getBaseDn();
      this.windowSize = replServerStartMsg.getWindowSize();
      this.serverState = replServerStartMsg.getServerState();
      this.sslEncryption = replServerStartMsg.getSSLEncryption();
      this.degradedStatusThreshold =
        replServerStartMsg.getDegradedStatusThreshold();
    }

    /**
     * Constructs a ServerInfo object wrapping a ReplServerStartDSMsg.
     * @param replServerStartDSMsg The ReplServerStartDSMsg this object will
     * wrap.
     */
    private ServerInfo(ReplServerStartDSMsg replServerStartDSMsg)
    {
      this.protocolVersion = replServerStartDSMsg.getVersion();
      this.generationId = replServerStartDSMsg.getGenerationId();
      this.groupId = replServerStartDSMsg.getGroupId();
      this.serverId = replServerStartDSMsg.getServerId();
      this.serverURL = replServerStartDSMsg.getServerURL();
      this.baseDn = replServerStartDSMsg.getBaseDn();
      this.windowSize = replServerStartDSMsg.getWindowSize();
      this.serverState = replServerStartDSMsg.getServerState();
      this.sslEncryption = replServerStartDSMsg.getSSLEncryption();
      this.degradedStatusThreshold =
        replServerStartDSMsg.getDegradedStatusThreshold();
      this.weight = replServerStartDSMsg.getWeight();
      this.connectedDSNumber = replServerStartDSMsg.getConnectedDSNumber();
    }

    /**
     * Get the server state.
     * @return The server state
     */
    public ServerState getServerState()
    {
      return serverState;
    }

    /**
     * get the group id.
     * @return The group id
     */
    public byte getGroupId()
    {
      return groupId;
    }

    /**
     * Get the server protocol version.
     * @return the protocolVersion
     */
    public short getProtocolVersion()
    {
      return protocolVersion;
    }

    /**
     * Get the generation id.
     * @return the generationId
     */
    public long getGenerationId()
    {
      return generationId;
    }

    /**
     * Get the server id.
     * @return the serverId
     */
    public int getServerId()
    {
      return serverId;
    }

    /**
     * Get the server URL.
     * @return the serverURL
     */
    public String getServerURL()
    {
      return serverURL;
    }

    /**
     * Get the base dn.
     * @return the baseDn
     */
    public String getBaseDn()
    {
      return baseDn;
    }

    /**
     * Get the window size.
     * @return the windowSize
     */
    public int getWindowSize()
    {
      return windowSize;
    }

    /**
     * Get the ssl encryption.
     * @return the sslEncryption
     */
    public boolean isSslEncryption()
    {
      return sslEncryption;
    }

    /**
     * Get the degraded status threshold.
     * @return the degradedStatusThreshold
     */
    public int getDegradedStatusThreshold()
    {
      return degradedStatusThreshold;
    }

    /**
     * Get the weight.
     * @return the weight. Null if this object is a wrapper for
     * a ReplServerStartMsg.
     */
    public int getWeight()
    {
      return weight;
    }

    /**
     * Get the connected DS number.
     * @return the connectedDSNumber. Null if this object is a wrapper for
     * a ReplServerStartMsg.
     */
    public int getConnectedDSNumber()
    {
      return connectedDSNumber;
    }
  }

  private void connect()
  {
    if (this.baseDn.compareToIgnoreCase(
        ServerConstants.DN_EXTERNAL_CHANGELOG_ROOT)==0)
    {
      connectAsECL();
    }
    else
    {
      connectAsDataServer();
    }
  }

  /**
   * Contacts all replication servers to get information from them and being
   * able to choose the more suitable.
   * @return the collected information.
   */
  private Map<String, ServerInfo> collectReplicationServersInfo() {

    Map<String, ServerInfo> rsInfos = new HashMap<String, ServerInfo>();

    for (String server : servers)
    {
      // Connect to server and get info about it
      ServerInfo serverInfo = performPhaseOneHandshake(server, false);

      // Store server info in list
      if (serverInfo != null)
      {
        rsInfos.put(server, serverInfo);
      }
    }

    return rsInfos;
  }

  /**
   * Special aspects of connecting as ECL compared to connecting as data server
   * are :
   * - 1 single RS configured
   * - so no choice of the preferred RS
   * - No same groupID polling
   * - ?? Heartbeat
   * - Start handshake is :
   *    Broker ---> StartECLMsg       ---> RS
   *          <---- ReplServerStartMsg ---
   *           ---> StartSessionECLMsg --> RS
   */
  private void connectAsECL()
  {
    // FIXME:ECL List of RS to connect is for now limited to one RS only
    String bestServer = this.servers.iterator().next();

    ReplServerStartDSMsg inReplServerStartDSMsg
      = performECLPhaseOneHandshake(bestServer, true);

    if (inReplServerStartDSMsg!=null)
      performECLPhaseTwoHandshake(bestServer);
  }

  /**
   * Connect to a ReplicationServer.
   *
   * Handshake sequences between a DS and a RS is divided into 2 logical
   * consecutive phases (phase 1 and phase 2). DS always initiates connection
   * and always sends first message:
   *
   * DS<->RS:
   * -------
   *
   * phase 1:
   * DS --- ServerStartMsg ---> RS
   * DS <--- ReplServerStartMsg --- RS
   * phase 2:
   * DS --- StartSessionMsg ---> RS
   * DS <--- TopologyMsg --- RS
   *
   * Before performing a full handshake sequence, DS searches for best suitable
   * RS by making only phase 1 handshake to every RS he knows then closing
   * connection. This allows to gather information on available RSs and then
   * decide with which RS the full handshake (phase 1 then phase 2) will be
   * finally performed.
   *
   * @throws NumberFormatException address was invalid
   */
  private void connectAsDataServer()
  {
    // May have created a broker with null replication domain for
    // unit test purpose.
    if (domain != null)
    {
      // If a first connect or a connection failure occur, we go through here.
      // force status machine to NOT_CONNECTED_STATUS so that monitoring can
      // see that we are not connected.
      domain.toNotConnectedStatus();
    }

    // Stop any existing poller and heartbeat monitor from a previous session.
    stopSameGroupIdPoller();
    stopRSHeartBeatMonitoring();
    stopChangeTimeHeartBeatPublishing();

    boolean newServerWithSameGroupId = false;
    synchronized (connectPhaseLock)
    {
      /*
       * Connect to each replication server and get their ServerState then find
       * out which one is the best to connect to.
       */
      if (debugEnabled())
        TRACER.debugInfo("phase 1 : will perform PhaseOneH with each RS in " +
            " order to elect the preferred one");

      // Get info from every available replication servers
      Map<String, ServerInfo> rsInfos = collectReplicationServersInfo();

      ServerInfo serverInfo = null;

      if (rsInfos.size() > 0)
      {
        // At least one server answered, find the best one.
        String bestServer = computeBestReplicationServer(state, rsInfos,
          serverId, baseDn, groupId);

        // Best found, now initialize connection to this one (handshake phase 1)
        if (debugEnabled())
          TRACER.debugInfo(
              "phase 2 : will perform PhaseOneH with the preferred RS.");
        serverInfo = performPhaseOneHandshake(bestServer, true);

        if (serverInfo != null) // Handshake phase 1 exchange went well

        {
          // Compute in which status we are starting the session to tell the RS
          ServerStatus initStatus =
            computeInitialServerStatus(serverInfo.getGenerationId(),
            serverInfo.getServerState(),
            serverInfo.getDegradedStatusThreshold(),
            this.getGenerationID());

          // Perfom session start (handshake phase 2)
          TopologyMsg topologyMsg = performPhaseTwoHandshake(bestServer,
            initStatus);

          if (topologyMsg != null) // Handshake phase 2 exchange went well

          {
            try
            {
              /*
               * If we just connected to a RS with a different group id than us
               * (because for instance a RS with our group id was unreachable
               * while connecting to each RS) but the just received TopologyMsg
               * shows that in the same time a RS with our group id connected,
               * we must give up the connection to force reconnection that will
               * certainly go back to a server with our group id as server with
               * our group id have a greater priority for connection (in
               * computeBestReplicationServer). In other words, we disconnect to
               * connect to a server with our group id. If a server with our
               * group id comes back later in the topology, we will be advised
               * upon reception of a new TopologyMsg message and we will force
               * reconnection at that time to retrieve a server with our group
               * id.
               */
              byte tmpRsGroupId = serverInfo.getGroupId();
              boolean someServersWithSameGroupId =
                hasSomeServerWithSameGroupId(topologyMsg.getRsList());

              // Really no other server with our group id ?
              if ((tmpRsGroupId == groupId) ||
                ((tmpRsGroupId != groupId) && !someServersWithSameGroupId))
              {
                replicationServer = session.getReadableRemoteAddress();
                maxSendWindow = serverInfo.getWindowSize();
                rsGroupId = serverInfo.getGroupId();
                rsServerId = serverInfo.getServerId();
                rsServerUrl = bestServer;

                receiveTopo(topologyMsg);

                // Log a message to let the administrator know that the failure
                // was resolved.
                // Wakeup all the thread that were waiting on the window
                // on the previous connection.
                connectionError = false;
                if (sendWindow != null)
                {
                  sendWindow.release(Integer.MAX_VALUE);
                }
                sendWindow = new Semaphore(maxSendWindow);
                rcvWindow = maxRcvWindow;
                connected = true;

                // May have created a broker with null replication domain for
                // unit test purpose.
                if (domain != null)
                {
                  domain.sessionInitiated(
                      initStatus, serverInfo.getServerState(),
                      serverInfo.getGenerationId(),
                      session);
                }

                if (getRsGroupId() != groupId)
                {
                 // Connected to replication server with wrong group id:
                 // warn user and start poller to recover when a server with
                 // right group id arrives...
                 Message message =
                   WARN_CONNECTED_TO_SERVER_WITH_WRONG_GROUP_ID.get(
                   Byte.toString(groupId),  Integer.toString(rsServerId),
                   bestServer, Byte.toString(getRsGroupId()),
                   baseDn.toString(), Integer.toString(serverId));
                 logError(message);
                 startSameGroupIdPoller();
                }
                startRSHeartBeatMonitoring();
                if (serverInfo.getProtocolVersion()
                    >= ProtocolVersion.REPLICATION_PROTOCOL_V3)
                {
                  startChangeTimeHeartBeatPublishing();
                }
              } else
              {
                // Detected new RS with our group id: log disconnection to
                // inform administrator
                Message message = NOTE_NEW_SERVER_WITH_SAME_GROUP_ID.get(
                  Byte.toString(groupId), baseDn.toString(),
                  Integer.toString(serverId));
                logError(message);
                // Do not log connection error
                newServerWithSameGroupId = true;
              }
            } catch (Exception e)
            {
              Message message = ERR_COMPUTING_FAKE_OPS.get(
                baseDn, bestServer,
                e.getLocalizedMessage() + stackTraceToSingleLineString(e));
              logError(message);
            } finally
            {
              if (connected == false)
              {
                if (session != null)
                {
                  try
                  {
                    session.close();
                  } catch (IOException e)
                  {
                    // The session was already closed, just ignore.
                  }
                  session = null;
                }
              }
            }
          } // Could perform handshake phase 2 with best

        } // Could perform handshake phase 1 with best

      } // Reached some servers

      if (connected)
      {
        connectPhaseLock.notify();

        if ((serverInfo.getGenerationId() == this.getGenerationID()) ||
          (serverInfo.getGenerationId() == -1))
        {
          Message message =
            NOTE_NOW_FOUND_SAME_GENERATION_CHANGELOG.get(
            baseDn.toString(),
            Integer.toString(rsServerId),
            replicationServer,
            Integer.toString(serverId),
            Long.toString(this.getGenerationID()));
          logError(message);
        } else
        {
          Message message =
            NOTE_NOW_FOUND_BAD_GENERATION_CHANGELOG.get(
            baseDn.toString(),
            replicationServer,
            Long.toString(this.getGenerationID()),
            Long.toString(serverInfo.getGenerationId()));
          logError(message);
        }
      } else
      {
        /*
         * This server could not find any replicationServer. It's going to start
         * in degraded mode. Log a message.
         */
        if (!connectionError && !newServerWithSameGroupId)
        {
          connectionError = true;
          connectPhaseLock.notify();
          Message message =
            NOTE_COULD_NOT_FIND_CHANGELOG.get(baseDn.toString());
          logError(message);
        }
      }
    }
  }

  /**
   * Has the passed RS info list some servers with our group id ?
   * @return true if at least one server has the same group id
   */
  private boolean hasSomeServerWithSameGroupId(List<RSInfo> rsInfos)
  {
    for (RSInfo rsInfo : rsInfos)
    {
      if (rsInfo.getGroupId() == this.groupId)
        return true;
    }
    return false;
  }

  /**
   * Determines the status we are starting with according to our state and the
   * RS state.
   *
   * @param rsGenId The generation id of the RS
   * @param rsState The server state of the RS
   * @param degradedStatusThreshold The degraded status threshold of the RS
   * @param dsGenId The local generation id
   * @return The initial status
   */
  public ServerStatus computeInitialServerStatus(long rsGenId,
    ServerState rsState, int degradedStatusThreshold, long dsGenId)
  {
    if (rsGenId == -1)
    {
      // RS has no generation id
      return ServerStatus.NORMAL_STATUS;
    } else
    {
      if (rsGenId == dsGenId)
      {
        // DS and RS have same generation id

        // Determine if we are late or not to replay changes. RS uses a
        // threshold value for pending changes to be replayed by a DS to
        // determine if the DS is in normal status or in degraded status.
        // Let's compare the local and remote server state using  this threshold
        // value to determine if we are late or not

        ServerStatus initStatus = ServerStatus.INVALID_STATUS;
        int nChanges = ServerState.diffChanges(rsState, state);

        if (debugEnabled())
        {
          TRACER.debugInfo("RB for dn " + baseDn +
            " and with server id " + Integer.toString(serverId) + " computed " +
            Integer.toString(nChanges) + " changes late.");
        }

        // Check status to know if it is relevant to change the status. Do not
        // take RSD lock to test. If we attempt to change the status whereas
        // we are in a status that do not allows that, this will be noticed by
        // the changeStatusFromStatusAnalyzer method. This allows to take the
        // lock roughly only when needed versus every sleep time timeout.
        if (degradedStatusThreshold > 0)
        {
          if (nChanges >= degradedStatusThreshold)
          {
            initStatus = ServerStatus.DEGRADED_STATUS;
          } else
          {
            initStatus = ServerStatus.NORMAL_STATUS;
          }
        } else
        {
          // 0 threshold value means no degrading system used (no threshold):
          // force normal status
          initStatus = ServerStatus.NORMAL_STATUS;
        }

        return initStatus;
      } else
      {
        // DS and RS do not have same generation id
        return ServerStatus.BAD_GEN_ID_STATUS;
      }
    }
  }

  /**
   * Connect to the provided server performing the first phase handshake
   * (start messages exchange) and return the reply message from the replication
   * server, wrapped in a ServerInfo object.
   *
   * @param server Server to connect to.
   * @param keepConnection Do we keep session opened or not after handshake.
   *        Use true if want to perform handshake phase 2 with the same session
   *        and keep the session to create as the current one.
   * @return The answer from the server . Null if could not
   *         get an answer.
   */
  private ServerInfo performPhaseOneHandshake(String server,
    boolean keepConnection)
  {
    ServerInfo serverInfo = null;

    // Parse server string.
    int separator = server.lastIndexOf(':');
    String port = server.substring(separator + 1);
    String hostname = server.substring(0, separator);
    ProtocolSession localSession = null;

    boolean error = false;
    try
    {
      /*
       * Open a socket connection to the next candidate.
       */
      int intPort = Integer.parseInt(port);
      InetSocketAddress serverAddr = new InetSocketAddress(
        InetAddress.getByName(hostname), intPort);
      Socket socket = new Socket();
      socket.setReceiveBufferSize(1000000);
      socket.setTcpNoDelay(true);
      socket.connect(serverAddr, 500);
      localSession = replSessionSecurity.createClientSession(server, socket,
        ReplSessionSecurity.HANDSHAKE_TIMEOUT);
      boolean isSslEncryption =
        replSessionSecurity.isSslEncryption(server);
      /*
       * Send our ServerStartMsg.
       */
      ServerStartMsg serverStartMsg = new ServerStartMsg(serverId, baseDn,
        maxRcvWindow, heartbeatInterval, state,
        ProtocolVersion.getCurrentVersion(), this.getGenerationID(),
        isSslEncryption,
        groupId);
      localSession.publish(serverStartMsg);

      /*
       * Read the ReplServerStartMsg or ReplServerStartDSMsg that should come
       * back.
       */
      ReplicationMsg msg = localSession.receive();

      if (debugEnabled())
        {
          TRACER.debugInfo("In RB for " + baseDn +
            "\nRB HANDSHAKE SENT:\n" + serverStartMsg.toString() +
            "\nAND RECEIVED:\n" + msg.toString());
        }

      // Wrap received message in a server info object
      serverInfo = ServerInfo.newServerInfo(msg);

      // Sanity check
      String repDn = serverInfo.getBaseDn();
      if (!(this.baseDn.equals(repDn)))
      {
        Message message = ERR_DS_DN_DOES_NOT_MATCH.get(repDn.toString(),
          this.baseDn);
        logError(message);
        error = true;
      }

      /*
       * We have sent our own protocol version to the replication server.
       * The replication server will use the same one (or an older one
       * if it is an old replication server).
       */
      protocolVersion = ProtocolVersion.minWithCurrent(
        serverInfo.getProtocolVersion());
      localSession.setProtocolVersion(protocolVersion);


      if (!isSslEncryption)
      {
        localSession.stopEncryption();
      }
    } catch (ConnectException e)
    {
      /*
       * There was no server waiting on this host:port
       * Log a notice and try the next replicationServer in the list
       */
      if (!connectionError)
      {
        Message message = NOTE_NO_CHANGELOG_SERVER_LISTENING.get(server);
        if (keepConnection) // Log error message only for final connection
        {
          // the error message is only logged once to avoid overflowing
          // the error log
          logError(message);
        } else if (debugEnabled())
        {
          TRACER.debugInfo(message.toString());
        }
      }
      error = true;
    } catch (Exception e)
    {
      if ( (e instanceof SocketTimeoutException) && debugEnabled() )
      {
        TRACER.debugInfo("Timeout trying to connect to RS " + server +
          " for dn: " + baseDn);
      }
      Message message = ERR_EXCEPTION_STARTING_SESSION_PHASE.get("1",
        baseDn, server, e.getLocalizedMessage() +
        stackTraceToSingleLineString(e));
      if (keepConnection) // Log error message only for final connection
      {
        logError(message);
      } else if (debugEnabled())
      {
        TRACER.debugInfo(message.toString());
      }
      error = true;
    }

    // Close session if requested
    if (!keepConnection || error)
    {
      if (localSession != null)
      {
        if (debugEnabled())
          TRACER.debugInfo("In RB, closing session after phase 1");

        if (protocolVersion >= ProtocolVersion.REPLICATION_PROTOCOL_V4)
        {
          // V4 protocol introduces a StopMsg to properly end communications
          if (!error)
          {
            try
            {
              localSession.publish(new StopMsg());
            } catch (IOException ioe)
            {
              // Anyway, going to close session, so nothing to do
            }
          }
        }
        try
        {
          localSession.close();
        } catch (IOException e)
        {
          // The session was already closed, just ignore.
        }
        localSession = null;
      }
      if (error)
      {
        serverInfo = null;
      } // Be sure to return null.

    }

    // If this connection as the one to use for sending and receiving updates,
    // store it.
    if (keepConnection)
    {
      session = localSession;
    }

    return serverInfo;
  }

  /**
   * Connect to the provided server performing the first phase handshake
   * (start messages exchange) and return the reply message from the replication
   * server.
   *
   * @param server Server to connect to.
   * @param keepConnection Do we keep session opened or not after handshake.
   *        Use true if want to perform handshake phase 2 with the same session
   *        and keep the session to create as the current one.
   * @return The ReplServerStartDSMsg the server replied. Null if could not
   *         get an answer.
   */
  private ReplServerStartDSMsg performECLPhaseOneHandshake(String server,
    boolean keepConnection)
  {
    ReplServerStartDSMsg replServerStartDSMsg = null;

    // Parse server string.
    int separator = server.lastIndexOf(':');
    String port = server.substring(separator + 1);
    String hostname = server.substring(0, separator);
    ProtocolSession localSession = null;

    boolean error = false;
    try
    {
      /*
       * Open a socket connection to the next candidate.
       */
      int intPort = Integer.parseInt(port);
      InetSocketAddress serverAddr = new InetSocketAddress(
        InetAddress.getByName(hostname), intPort);
      Socket socket = new Socket();
      socket.setReceiveBufferSize(1000000);
      socket.setTcpNoDelay(true);
      socket.connect(serverAddr, 500);
      localSession = replSessionSecurity.createClientSession(server, socket,
        ReplSessionSecurity.HANDSHAKE_TIMEOUT);
      boolean isSslEncryption =
        replSessionSecurity.isSslEncryption(server);

      // Send our start msg.
      ServerStartECLMsg serverStartECLMsg = new ServerStartECLMsg(
          baseDn, 0, 0, 0, 0,
          maxRcvWindow, heartbeatInterval, state,
          ProtocolVersion.getCurrentVersion(), this.getGenerationID(),
          isSslEncryption,
          groupId);
      localSession.publish(serverStartECLMsg);

      // Read the ReplServerStartMsg that should come back.
      replServerStartDSMsg = (ReplServerStartDSMsg) localSession.receive();

      if (debugEnabled())
      {
        TRACER.debugInfo("In RB for " + baseDn +
          "\nRB HANDSHAKE SENT:\n" + serverStartECLMsg.toString() +
          "\nAND RECEIVED:\n" + replServerStartDSMsg.toString());
      }

      // Sanity check
      String repDn = replServerStartDSMsg.getBaseDn();
      if (!(this.baseDn.equals(repDn)))
      {
        Message message = ERR_DS_DN_DOES_NOT_MATCH.get(repDn.toString(),
          this.baseDn);
        logError(message);
        error = true;
      }

      /*
       * We have sent our own protocol version to the replication server.
       * The replication server will use the same one (or an older one
       * if it is an old replication server).
       */
      if (keepConnection)
        protocolVersion = ProtocolVersion.minWithCurrent(
          replServerStartDSMsg.getVersion());
      localSession.setProtocolVersion(protocolVersion);

      if (!isSslEncryption)
      {
        localSession.stopEncryption();
      }
    } catch (ConnectException e)
    {
      /*
       * There was no server waiting on this host:port
       * Log a notice and try the next replicationServer in the list
       */
      if (!connectionError)
      {
        Message message = NOTE_NO_CHANGELOG_SERVER_LISTENING.get(server);
        if (keepConnection) // Log error message only for final connection
        {
          // the error message is only logged once to avoid overflowing
          // the error log
          logError(message);
        } else if (debugEnabled())
        {
          TRACER.debugInfo(message.toString());
        }
      }
      error = true;
    } catch (Exception e)
    {
      if ( (e instanceof SocketTimeoutException) && debugEnabled() )
      {
        TRACER.debugInfo("Timeout trying to connect to RS " + server +
          " for dn: " + baseDn);
      }
      Message message = ERR_EXCEPTION_STARTING_SESSION_PHASE.get("1",
        baseDn, server, e.getLocalizedMessage() +
        stackTraceToSingleLineString(e));
      if (keepConnection) // Log error message only for final connection
      {
        logError(message);
      } else if (debugEnabled())
      {
        TRACER.debugInfo(message.toString());
      }
      error = true;
    }

    // Close session if requested
    if (!keepConnection || error)
    {
      if (localSession != null)
      {
        if (debugEnabled())
          TRACER.debugInfo("In RB, closing session after phase 1");

        // V4 protocol introduces a StopMsg to properly end communications
        if (!error)
        {
          try
          {
            localSession.publish(new StopMsg());
          } catch (IOException ioe)
          {
            // Anyway, going to close session, so nothing to do
          }
        }
        try
        {
          localSession.close();
        } catch (IOException e)
        {
          // The session was already closed, just ignore.
        }
        localSession = null;
      }
      if (error)
      {
        replServerStartDSMsg = null;
      } // Be sure to return null.

    }

    // If this connection as the one to use for sending and receiving updates,
    // store it.
    if (keepConnection)
    {
      session = localSession;
    }

    return replServerStartDSMsg;
  }

  /**
   * Performs the second phase handshake (send StartSessionMsg and receive
   * TopologyMsg messages exchange) and return the reply message from the
   * replication server.
   *
   * @param server Server we are connecting with.
   * @param initStatus The status we are starting with
   * @return The ReplServerStartMsg the server replied. Null if could not
   *         get an answer.
   */
  private TopologyMsg performECLPhaseTwoHandshake(String server)
  {
    TopologyMsg topologyMsg = null;

    try
    {
      // Send our Start Session
      StartECLSessionMsg startECLSessionMsg = null;
      startECLSessionMsg = new StartECLSessionMsg();
      startECLSessionMsg.setOperationId("-1");
      session.publish(startECLSessionMsg);

      /* FIXME:ECL In the handshake phase two, should RS send back a topo msg ?
       * Read the TopologyMsg that should come back.
      topologyMsg = (TopologyMsg) session.receive();
       */
      if (debugEnabled())
      {
        TRACER.debugInfo("In RB for " + baseDn +
          "\nRB HANDSHAKE SENT:\n" + startECLSessionMsg.toString());
      //  +   "\nAND RECEIVED:\n" + topologyMsg.toString());
      }

      // Alright set the timeout to the desired value
      session.setSoTimeout(timeout);
      connected = true;

    } catch (Exception e)
    {
      Message message = ERR_EXCEPTION_STARTING_SESSION_PHASE.get("2",
        baseDn, server, e.getLocalizedMessage() +
        stackTraceToSingleLineString(e));
      logError(message);

      if (session != null)
      {
        try
        {
          session.close();
        } catch (IOException ex)
        {
          // The session was already closed, just ignore.
        }
        session = null;
      }
      // Be sure to return null.
      topologyMsg = null;
    }
    return topologyMsg;
  }

  /**
   * Performs the second phase handshake (send StartSessionMsg and receive
   * TopologyMsg messages exchange) and return the reply message from the
   * replication server.
   *
   * @param server Server we are connecting with.
   * @param initStatus The status we are starting with
   * @return The ReplServerStartMsg the server replied. Null if could not
   *         get an answer.
   */
  private TopologyMsg performPhaseTwoHandshake(String server,
    ServerStatus initStatus)
  {
    TopologyMsg topologyMsg = null;

    try
    {
      /*
       * Send our StartSessionMsg.
       */
      StartSessionMsg startSessionMsg = null;
      // May have created a broker with null replication domain for
      // unit test purpose.
      if (domain != null)
      {
        startSessionMsg =
          new StartSessionMsg(
              initStatus,
              domain.getRefUrls(),
              domain.isAssured(),
              domain.getAssuredMode(),
              domain.getAssuredSdLevel());
        startSessionMsg.setEclIncludes(
            domain.getEclInclude());
      } else
      {
        startSessionMsg =
          new StartSessionMsg(initStatus, new ArrayList<String>());
      }
      session.publish(startSessionMsg);

      /*
       * Read the TopologyMsg that should come back.
       */
      topologyMsg = (TopologyMsg) session.receive();

      if (debugEnabled())
      {
        TRACER.debugInfo("In RB for " + baseDn +
          "\nRB HANDSHAKE SENT:\n" + startSessionMsg.toString() +
          "\nAND RECEIVED:\n" + topologyMsg.toString());
      }

      // Alright set the timeout to the desired value
      session.setSoTimeout(timeout);

    } catch (Exception e)
    {
      Message message = ERR_EXCEPTION_STARTING_SESSION_PHASE.get("2",
        baseDn, server, e.getLocalizedMessage() +
        stackTraceToSingleLineString(e));
      logError(message);

      if (session != null)
      {
        try
        {
          session.close();
        } catch (IOException ex)
        {
          // The session was already closed, just ignore.
        }
        session = null;
      }
      // Be sure to return null.
      topologyMsg = null;
    }
    return topologyMsg;
  }

  /**
   * Returns the replication server that best fits our need so that we can
   * connect to it.
   * This methods performs some filtering on the group id, then call
   * the real search for best server algorithm (searchForBestReplicationServer).
   *
   * Note: this method put as public static for unit testing purpose.
   *
   * @param myState The local server state.
   * @param rsInfos The list of available replication servers and their
   *                 associated information (choice will be made among them).
   * @param localServerId The server id for the suffix we are working for.
   * @param baseDn The suffix for which we are working for.
   * @param groupId The groupId we prefer being connected to if possible
   * @return The computed best replication server.
   */
  public static String computeBestReplicationServer(ServerState myState,
    Map<String, ServerInfo> rsInfos, int localServerId,
    String baseDn, byte groupId)
  {
    /*
     * Preference is given to servers with the requested group id:
     * If there are some servers with the requested group id in the provided
     * server list, then we run the search algorithm only on them. If no server
     * with the requested group id, consider all of them.
     */

    // Filter for servers with same group id
    Map<String, ServerInfo> sameGroupIdRsInfos =
      new HashMap<String, ServerInfo>();

    for (String repServer : rsInfos.keySet())
    {
      ServerInfo serverInfo = rsInfos.get(repServer);
      if (serverInfo.getGroupId() == groupId)
        sameGroupIdRsInfos.put(repServer, serverInfo);
    }

    // Some servers with same group id ?
    if (sameGroupIdRsInfos.size() > 0)
    {
      return searchForBestReplicationServer(myState, sameGroupIdRsInfos,
        localServerId, baseDn);
    } else
    {
      return searchForBestReplicationServer(myState, rsInfos,
        localServerId, baseDn);
    }
  }

  /**
   * Returns the replication server that best fits our need so that we can
   * connect to it.
   *
   * Note: this method put as public static for unit testing purpose.
   *
   * @param myState The local server state.
   * @param rsInfos The list of available replication servers and their
   *                 associated information (choice will be made among them).
   * @param localServerID The server id for the suffix we are working for.
   * @param baseDn The suffix for which we are working for.
   * @return The computed best replication server.
   */
  private static String searchForBestReplicationServer(ServerState myState,
    Map<String, ServerInfo> rsInfos, int localServerID, String baseDn)
  {
    /*
     * Find replication servers who are up to date (or more up to date than us,
     * if for instance we failed and restarted, having sent some changes to the
     * RS but without having time to store our own state) regarding our own
     * server id. Then, among them, choose the server that is the most up to
     * date regarding the whole topology.
     *
     * If no server is up to date regarding our own server id, find the one who
     * is the most up to date regarding our server id.
     */

    // Should never happen (sanity check)
    if ((myState == null) || (rsInfos == null) || (rsInfos.size() < 1) ||
      (baseDn == null))
    {
      return null;
    }

    // Shortcut, if only one server, this is the best
    if (rsInfos.size() == 1)
    {
      for (String repServer : rsInfos.keySet())
        return repServer;
    }

    String bestServer = null;
    boolean bestServerIsLocal = false;

    // Servers up to dates with regard to our changes
    HashMap<String, ServerState> upToDateServers =
      new HashMap<String, ServerState>();
    // Servers late with regard to our changes
    HashMap<String, ServerState> lateOnes = new HashMap<String, ServerState>();

    /*
     * Start loop to differentiate up to date servers from late ones.
     */
    ChangeNumber myChangeNumber = myState.getMaxChangeNumber(localServerID);
    if (myChangeNumber == null)
    {
      myChangeNumber = new ChangeNumber(0, 0, localServerID);
    }
    for (String repServer : rsInfos.keySet())
    {

      ServerState rsState = rsInfos.get(repServer).getServerState();
      ChangeNumber rsChangeNumber = rsState.getMaxChangeNumber(localServerID);
      if (rsChangeNumber == null)
      {
        rsChangeNumber = new ChangeNumber(0, 0, localServerID);
      }

      // Store state in right list
      if (myChangeNumber.olderOrEqual(rsChangeNumber))
      {
        upToDateServers.put(repServer, rsState);
      } else
      {
        lateOnes.put(repServer, rsState);
      }
    }

    if (upToDateServers.size() > 0)
    {
      /*
       * Some up to date servers, among them, choose either :
       * - The local one
       * - The one that has the maximum number of changes to send us.
       *   This is the most up to date one regarding the whole topology.
       *   This server is the one which has the less
       *   difference with the topology server state.
       *   For comparison, we need to compute the difference for each
       *   server id with the topology server state.
       */

      Message message = NOTE_FOUND_CHANGELOGS_WITH_MY_CHANGES.get(
        upToDateServers.size(), baseDn, Integer.toString(localServerID));
      logError(message);

      /*
       * First of all, compute the virtual server state for the whole topology,
       * which is composed of the most up to date change numbers for
       * each server id in the topology.
       */
      ServerState topoState = new ServerState();
      for (ServerState curState : upToDateServers.values())
      {

        Iterator<Integer> it = curState.iterator();
        while (it.hasNext())
        {
          Integer sId = it.next();
          ChangeNumber curSidCn = curState.getMaxChangeNumber(sId);
          if (curSidCn == null)
          {
            curSidCn = new ChangeNumber(0, 0, sId);
          }
          // Update topology state
          topoState.update(curSidCn);
        }
      } // For up to date servers

      // Min of the max shifts
      long minShift = -1L;
      for (String upServer : upToDateServers.keySet())
      {

        /*
         * Compute the maximum difference between the time of a server id's
         * change number and the time of the matching server id's change
         * number in the topology server state.
         *
         * Note: we could have used the sequence number here instead of the
         * timestamp, but this would have caused a problem when the sequence
         * number loops and comes back to 0 (computation would have becomen
         * meaningless).
         */
        long shift = 0;
        ServerState curState = upToDateServers.get(upServer);
        Iterator<Integer> it = curState.iterator();
        while (it.hasNext())
        {
          Integer sId = it.next();
          ChangeNumber curSidCn = curState.getMaxChangeNumber(sId);
          if (curSidCn == null)
          {
            curSidCn = new ChangeNumber(0, 0, sId);
          }
          // Cannot be null as checked at construction time
          ChangeNumber topoCurSidCn = topoState.getMaxChangeNumber(sId);
          // Cannot be negative as topoState computed as being the max CN
          // for each server id in the topology
          long tmpShift = topoCurSidCn.getTime() - curSidCn.getTime();
          shift +=tmpShift;
        }

        boolean upServerIsLocal =
          ReplicationServer.isLocalReplicationServer(upServer);
        if ((minShift < 0) // First time in loop
            || ((shift < minShift) && upServerIsLocal)
            || (((bestServerIsLocal == false) && (shift < minShift)))
            || ((bestServerIsLocal == false) && (upServerIsLocal &&
                                              (shift<(minShift + 60)) ))
            || (shift+120 < minShift))
        {
          // This server is even closer to topo state
          bestServer = upServer;
          bestServerIsLocal = upServerIsLocal;
          minShift = shift;
        }
      } // For up to date servers

    } else
    {
      /*
       * We could not find a replication server that has seen all the
       * changes that this server has already processed,
       */
      // lateOnes cannot be empty
      Message message = NOTE_COULD_NOT_FIND_CHANGELOG_WITH_MY_CHANGES.get(
        baseDn, lateOnes.size());
      logError(message);

      // Min of the shifts
      long minShift = -1L;
      for (String lateServer : lateOnes.keySet())
      {
        /*
         * Choose the server who is the closest to us regarding our server id
         * (this is the most up to date regarding our server id).
         */
        ServerState curState = lateOnes.get(lateServer);
        ChangeNumber ourSidCn = curState.getMaxChangeNumber(localServerID);
        if (ourSidCn == null)
        {
          ourSidCn = new ChangeNumber(0, 0, localServerID);
        }
        // Cannot be negative as our Cn for our server id is strictly
        // greater than those of the servers in late server list
        long tmpShift = myChangeNumber.getTime() - ourSidCn.getTime();

        boolean lateServerisLocal =
          ReplicationServer.isLocalReplicationServer(lateServer);
        if ((minShift < 0) // First time in loop
          || ((tmpShift < minShift) && lateServerisLocal)
          || (((bestServerIsLocal == false) && (tmpShift < minShift)))
          || ((bestServerIsLocal == false) && (lateServerisLocal &&
                                            (tmpShift<(minShift + 60)) ))
          || (tmpShift+120 < minShift))
        {
          // This server is even closer to topo state
          bestServer = lateServer;
          bestServerIsLocal = lateServerisLocal;
          minShift = tmpShift;
        }
      } // For late servers

    }
    return bestServer;
  }

  /**
   * Start the heartbeat monitor thread.
   */
  private void startRSHeartBeatMonitoring()
  {
    // Start a heartbeat monitor thread.
    if (heartbeatInterval > 0)
    {
      heartbeatMonitor =
        new HeartbeatMonitor("Replication Heartbeat Monitor on RS " +
        getReplicationServer() + " " + rsServerId + " for " + baseDn +
        " in DS " + serverId,
        session, heartbeatInterval, (protocolVersion >=
        ProtocolVersion.REPLICATION_PROTOCOL_V4));
      heartbeatMonitor.start();
    }
  }

  /**
   * Starts the same group id poller.
   */
  private void startSameGroupIdPoller()
  {
    sameGroupIdPoller = new SameGroupIdPoller();
    sameGroupIdPoller.start();
  }

  /**
   * Stops the same group id poller.
   */
  private void stopSameGroupIdPoller()
  {
    if (sameGroupIdPoller != null)
    {
      sameGroupIdPoller.shutdown();
      sameGroupIdPoller.waitForShutdown();
      sameGroupIdPoller = null;
    }
  }

  /**
   * Stop the heartbeat monitor thread.
   */
  void stopRSHeartBeatMonitoring()
  {
    if (heartbeatMonitor != null)
    {
      heartbeatMonitor.shutdown();
      heartbeatMonitor = null;
    }
  }

  /**
   * restart the ReplicationBroker.
   */
  public void reStart()
  {
    reStart(this.session);
  }

  /**
   * Restart the ReplicationServer broker after a failure.
   *
   * @param failingSession the socket which failed
   */
  public void reStart(ProtocolSession failingSession)
  {

    if (failingSession != null)
    {
      if (protocolVersion >= ProtocolVersion.REPLICATION_PROTOCOL_V4)
      {
        // V4 protocol introduces a StopMsg to properly end communications
        try
        {
          failingSession.publish(new StopMsg());
        } catch (IOException ioe)
        {
          // Anyway, going to close session, so nothing to do
        }
      }
      try
      {
        failingSession.close();
      } catch (IOException e1)
      {
        // ignore
      }
      numLostConnections++;
    }

    if (failingSession == session)
    {
      this.connected = false;
      rsGroupId = (byte) -1;
      rsServerId = -1;
      rsServerUrl = null;
    }
    while (!this.connected && (!this.shutdown))
    {
      try
      {
        this.connect();
      } catch (Exception e)
      {
        MessageBuilder mb = new MessageBuilder();
        mb.append(NOTE_EXCEPTION_RESTARTING_SESSION.get(
          baseDn, e.getLocalizedMessage()));
        mb.append(stackTraceToSingleLineString(e));
        logError(mb.toMessage());
      }
      if ((!connected) && (!shutdown))
      {
        try
        {
          Thread.sleep(500);
        } catch (InterruptedException e)
        {
          // ignore
        }
      }
    }
  }

  /**
   * Publish a message to the other servers.
   * @param msg the message to publish
   */
  public void publish(ReplicationMsg msg)
  {
    _publish(msg, false);
  }

  /**
   * Publish a recovery message to the other servers.
   * @param msg the message to publish
   */
  public void publishRecovery(ReplicationMsg msg)
  {
    _publish(msg, true);
  }

  /**
   * Publish a message to the other servers.
   * @param msg the message to publish
   * @param recoveryMsg the message is a recovery Message
   */
  void _publish(ReplicationMsg msg, boolean recoveryMsg)
  {
    boolean done = false;

    while (!done && !shutdown)
    {
      if (connectionError)
      {
        // It was not possible to connect to any replication server.
        // Since the operation was already processed, we have no other
        // choice than to return without sending the ReplicationMsg
        // and relying on the resend procedure of the connect phase to
        // fix the problem when we finally connect.

        if (debugEnabled())
        {
          debugInfo("ReplicationBroker.publish() Publishing a " +
            "message is not possible due to existing connection error.");
        }

        return;
      }

      try
      {
        boolean credit;
        ProtocolSession current_session;
        Semaphore currentWindowSemaphore;

        // save the session at the time when we acquire the
        // sendwindow credit so that we can make sure later
        // that the session did not change in between.
        // This is necessary to make sure that we don't publish a message
        // on a session with a credit that was acquired from a previous
        // session.
        synchronized (connectPhaseLock)
        {
          current_session = session;
          currentWindowSemaphore = sendWindow;
        }

        // If the Replication domain has decided that there is a need to
        // recover some changes then it is not allowed to send this
        // change but it will be the responsibility of the recovery thread to
        // do it.
        if (!recoveryMsg & connectRequiresRecovery)
        {
          return;
        }

        if (msg instanceof UpdateMsg)
        {
          // Acquiring the window credit must be done outside of the
          // connectPhaseLock because it can be blocking and we don't
          // want to hold off reconnection in case the connection dropped.
          credit =
            currentWindowSemaphore.tryAcquire(
            (long) 500, TimeUnit.MILLISECONDS);
        } else
        {
          credit = true;
        }
        if (credit)
        {
          synchronized (connectPhaseLock)
          {
            // check the session. If it has changed, some
            // deconnection/reconnection happened and we need to restart from
            // scratch.
            if (session == current_session)
            {
              session.publish(msg);
              done = true;
            }
          }
        }
        if ((!credit) && (currentWindowSemaphore.availablePermits() == 0))
        {
          // the window is still closed.
          // Send a WindowProbeMsg message to wakeup the receiver in case the
          // window update message was lost somehow...
          // then loop to check again if connection was closed.
          session.publish(new WindowProbeMsg());
        }
      } catch (IOException e)
      {
        // The receive threads should handle reconnection or
        // mark this broker in error. Just retry.
        synchronized (connectPhaseLock)
        {
          try
          {
            connectPhaseLock.wait(100);
          } catch (InterruptedException e1)
          {
            // ignore
            if (debugEnabled())
            {
              debugInfo("ReplicationBroker.publish() " +
                "Interrupted exception raised : " + e.getLocalizedMessage());
            }
          }
        }
      } catch (InterruptedException e)
      {
        // just loop.
        if (debugEnabled())
        {
          debugInfo("ReplicationBroker.publish() " +
            "Interrupted exception raised." + e.getLocalizedMessage());
        }
      }
    }
  }

  /**
   * Receive a message.
   * This method is not multithread safe and should either always be
   * called in a single thread or protected by a locking mechanism
   * before being called.
   *
   * @return the received message
   * @throws SocketTimeoutException if the timeout set by setSoTimeout
   *         has expired
   */
  public ReplicationMsg receive() throws SocketTimeoutException
  {
    while (shutdown == false)
    {
      if (!connected)
      {
        reStart(null);
      }

      ProtocolSession failingSession = session;
      try
      {
        ReplicationMsg msg = session.receive();
        if (msg instanceof UpdateMsg)
        {
          synchronized (this)
          {
            rcvWindow--;
          }
        }
        if (msg instanceof WindowMsg)
        {
          WindowMsg windowMsg = (WindowMsg) msg;
          sendWindow.release(windowMsg.getNumAck());
        }
        else if (msg instanceof TopologyMsg)
        {
          TopologyMsg topoMsg = (TopologyMsg)msg;
          receiveTopo(topoMsg);
        }
        else if (msg instanceof StopMsg)
        {
          /*
           * RS performs a proper disconnection
           */
          Message message =
            NOTE_REPLICATION_SERVER_PROPERLY_DISCONNECTED.get(replicationServer,
            Integer.toString(rsServerId), baseDn.toString(),
            Integer.toString(serverId));
          logError(message);
          // Try to find a suitable RS
          this.reStart(failingSession);
        }
        else if (msg instanceof MonitorMsg)
        {
          // This is the response to a MonitorRequest that was sent earlier or
          // the regular message of the monitoring publisher of the RS.

          // Extract and store replicas ServerStates
          replicaStates = new HashMap<Integer, ServerState>();
          MonitorMsg monitorMsg = (MonitorMsg) msg;
          Iterator<Integer> it = monitorMsg.ldapIterator();
          while (it.hasNext())
          {
            int srvId = it.next();
            replicaStates.put(srvId, monitorMsg.getLDAPServerState(srvId));
          }

          // Notify the sender that the response was received.
          synchronized (monitorResponse)
          {
            monitorResponse.set(true);
            monitorResponse.notify();
          }

          // Extract and store replication servers ServerStates
          rsStates = new HashMap<Integer, ServerState>();
          it = monitorMsg.rsIterator();
          while (it.hasNext())
          {
            int srvId = it.next();
            rsStates.put(srvId, monitorMsg.getRSServerState(srvId));
          }
        }
        else
        {
          return msg;
        }
      } catch (SocketTimeoutException e)
      {
        throw e;
      } catch (Exception e)
      {
        if (shutdown == false)
        {
          if ((session == null) || (!session.closeInitiated()))

          {
            /*
             * We did not initiate the close on our side, log an error message.
             */
            Message message =
              ERR_REPLICATION_SERVER_BADLY_DISCONNECTED.get(replicationServer,
                  Integer.toString(rsServerId), baseDn.toString(),
                  Integer.toString(serverId));
            logError(message);
          }
          this.reStart(failingSession);
        }
      }
    }
    return null;
  }

  /**
   * Gets the States of all the Replicas currently in the
   * Topology.
   * When this method is called, a Monitoring message will be sent
   * to the Replication Server to which this domain is currently connected
   * so that it computes a table containing information about
   * all Directory Servers in the topology.
   * This Computation involves communications will all the servers
   * currently connected and
   *
   * @return The States of all Replicas in the topology (except us)
   */
  public Map<Integer, ServerState> getReplicaStates()
  {
    monitorResponse.set(false);

    // publish Monitor Request Message to the Replication Server
    publish(new MonitorRequestMsg(serverId, getRsServerId()));

    // wait for Response up to 10 seconds.
    try
    {
      synchronized (monitorResponse)
      {
        if (monitorResponse.get() == false)
        {
          monitorResponse.wait(10000);
        }
      }
    } catch (InterruptedException e)
    {}
    return replicaStates;
  }

  /**
   * This method allows to do the necessary computing for the window
   * management after treatment by the worker threads.
   *
   * This should be called once the replay thread have done their job
   * and the window can be open again.
   */
  public synchronized void updateWindowAfterReplay()
  {
    try
    {
      updateDoneCount ++;
      if ((updateDoneCount >= halfRcvWindow) && (session != null))
      {
        session.publish(new WindowMsg(updateDoneCount));
        rcvWindow += updateDoneCount;
        updateDoneCount = 0;
      }
    } catch (IOException e)
    {
      // Any error on the socket will be handled by the thread calling receive()
      // just ignore.
    }
  }

  /**
   * stop the server.
   */
  public void stop()
  {
    if (debugEnabled())
    {
      debugInfo("ReplicationBroker " + serverId + " is stopping and will" +
        " close the connection to replication server " + rsServerId + " for"
        + " domain " + baseDn);
    }
    stopSameGroupIdPoller();
    stopRSHeartBeatMonitoring();
    stopChangeTimeHeartBeatPublishing();
    replicationServer = "stopped";
    shutdown = true;
    connected = false;
    rsGroupId = (byte) -1;
    rsServerId = -1;
    rsServerUrl = null;

    if (session != null)
    {
      if (protocolVersion >= ProtocolVersion.REPLICATION_PROTOCOL_V4)
      {
        // V4 protocol introduces a StopMsg to properly end communications
        try
        {
          session.publish(new StopMsg());
        } catch (IOException ioe)
        {
          // Anyway, going to close session, so nothing to do
        }
      }
      try
      {
        session.close();
      } catch (IOException e)
      {
      }
    }
  }

  /**
   * Set a timeout value.
   * With this option set to a non-zero value, calls to the receive() method
   * block for only this amount of time after which a
   * java.net.SocketTimeoutException is raised.
   * The Broker is valid and usable even after such an Exception is raised.
   *
   * @param timeout the specified timeout, in milliseconds.
   * @throws SocketException if there is an error in the underlying protocol,
   *         such as a TCP error.
   */
  public void setSoTimeout(int timeout) throws SocketException
  {
    this.timeout = timeout;
    if (session != null)
    {
      session.setSoTimeout(timeout);
    }
  }

  /**
   * Get the name of the replicationServer to which this broker is currently
   * connected.
   *
   * @return the name of the replicationServer to which this domain
   *         is currently connected.
   */
  public String getReplicationServer()
  {
    return replicationServer;
  }

  /**
   * Get the maximum receive window size.
   *
   * @return The maximum receive window size.
   */
  public int getMaxRcvWindow()
  {
    return maxRcvWindow;
  }

  /**
   * Get the current receive window size.
   *
   * @return The current receive window size.
   */
  public int getCurrentRcvWindow()
  {
    return rcvWindow;
  }

  /**
   * Get the maximum send window size.
   *
   * @return The maximum send window size.
   */
  public int getMaxSendWindow()
  {
    return maxSendWindow;
  }

  /**
   * Get the current send window size.
   *
   * @return The current send window size.
   */
  public int getCurrentSendWindow()
  {
    if (connected)
    {
      return sendWindow.availablePermits();
    } else
    {
      return 0;
    }
  }

  /**
   * Get the number of times the connection was lost.
   * @return The number of times the connection was lost.
   */
  public int getNumLostConnections()
  {
    return numLostConnections;
  }

  /**
   * Change some configuration parameters.
   *
   * @param replicationServers  The new list of replication servers.
   * @param window              The max window size.
   * @param heartbeatInterval   The heartBeat interval.
   *
   * @return                    A boolean indicating if the changes
   *                            requires to restart the service.
   * @param groupId            The new group id to use
   */
  public boolean changeConfig(
      Collection<String> replicationServers, int window, long heartbeatInterval,
      byte groupId)
  {
    // These parameters needs to be renegotiated with the ReplicationServer
    // so if they have changed, that requires restarting the session with
    // the ReplicationServer.
    Boolean needToRestartSession = false;

    // A new session is necessary only when information regarding
    // the connection is modified
    if ((servers == null) ||
        (!(replicationServers.size() == servers.size()
        && replicationServers.containsAll(servers))) ||
        window != this.maxRcvWindow  ||
        heartbeatInterval != this.heartbeatInterval ||
        (groupId != this.groupId))
    {
      needToRestartSession = true;
    }

    this.servers = replicationServers;
    this.rcvWindow = window;
    this.maxRcvWindow = window;
    this.halfRcvWindow = window / 2;
    this.heartbeatInterval = heartbeatInterval;
    this.groupId = groupId;

    return needToRestartSession;
  }

  /**
   * Get the version of the replication protocol.
   * @return The version of the replication protocol.
   */
  public short getProtocolVersion()
  {
    return protocolVersion;
  }

  /**
   * Check if the broker is connected to a ReplicationServer and therefore
   * ready to received and send Replication Messages.
   *
   * @return true if the server is connected, false if not.
   */
  public boolean isConnected()
  {
    return connected;
  }

  private boolean debugEnabled()
  {
    return false;
  }

  private static final void debugInfo(String s)
  {
    logError(Message.raw(Category.SYNC, Severity.NOTICE, s));
    TRACER.debugInfo(s);
  }

  /**
   * Determine whether the connection to the replication server is encrypted.
   * @return true if the connection is encrypted, false otherwise.
   */
  public boolean isSessionEncrypted()
  {
    boolean isEncrypted = false;
    if (session != null)
    {
      return session.isEncrypted();
    }
    return isEncrypted;
  }

  /**
   * In case we are connected to a RS with a different group id, we use this
   * thread to poll presence of a RS with the same group id as ours. If a RS
   * with the same group id is available, we close the session to force
   * reconnection. Reconnection will choose a server with the same group id.
   */
  private class SameGroupIdPoller extends DirectoryThread
  {

    private boolean sameGroupIdPollershutdown = false;
    private boolean terminated = false;
    // Sleep interval in ms
    private static final int SAME_GROUP_ID_POLLER_PERIOD = 5000;

    public SameGroupIdPoller()
    {
      super("Replication Broker Same Group Id Poller for " + baseDn.toString() +
        " and group id " + groupId + " in server id " + serverId);
    }

    /**
     * Wait for the completion of the same group id poller.
     */
    public void waitForShutdown()
    {
      try
      {
        while (terminated == false)
        {
          Thread.sleep(50);
        }
      } catch (InterruptedException e)
      {
        // exit the loop if this thread is interrupted.
      }
    }

    /**
     * Shutdown the same group id poller.
     */
    public void shutdown()
    {
      sameGroupIdPollershutdown = true;
    }

    /**
     * Permanently look for RS with our group id and if found, break current
     * connection to force reconnection to a new server with the right group id.
     */
    @Override
    public void run()
    {
      boolean done = false;

      if (debugEnabled())
      {
        TRACER.debugInfo("SameGroupIdPoller for: " + baseDn.toString() +
          " started.");
      }

      while ((!done) && (!sameGroupIdPollershutdown))
      {
        // Sleep some time between checks
        try
        {
          Thread.sleep(SAME_GROUP_ID_POLLER_PERIOD);
        } catch (InterruptedException e)
        {
          // Stop as we are interrupted
          sameGroupIdPollershutdown = true;
        }
        synchronized (connectPhaseLock)
        {
          if (debugEnabled())
          {
            TRACER.debugInfo("Running SameGroupIdPoller for: " +
              baseDn.toString());
          }
          if (session != null) // Check only if not already disconnected

          {
            for (String server : servers)
            {
              // Do not ask the RS we are connected to as it has for sure the
              // wrong group id
              if (server.equals(rsServerUrl))
                continue;

              // Connect to server and get reply message
              ServerInfo serverInfo =
                performPhaseOneHandshake(server, false);

              // Is it a server with our group id ?
              if (serverInfo != null)
              {
                if (groupId == serverInfo.getGroupId())
                {
                  // Found one server with the same group id as us, disconnect
                  // session to force reconnection to a server with same group
                  // id.
                  Message message = NOTE_NEW_SERVER_WITH_SAME_GROUP_ID.get(
                    Byte.toString(groupId), baseDn.toString(),
                    Integer.toString(serverId));
                  logError(message);

                  if (protocolVersion >=
                    ProtocolVersion.REPLICATION_PROTOCOL_V4)
                  {
                    // V4 protocol introduces a StopMsg to properly end
                    // communications
                    try
                    {
                      session.publish(new StopMsg());
                    } catch (IOException ioe)
                    {
                      // Anyway, going to close session, so nothing to do
                    }
                  }
                  try
                  {
                    session.close();
                  } catch (Exception e)
                  {
                    // The session was already closed, just ignore.
                  }
                  session = null;
                  done = true; // Terminates thread as did its job.

                  break;
                }
              }
            } // for server

          }
        }
      }

      terminated = true;
      if (debugEnabled())
      {
        TRACER.debugInfo("SameGroupIdPoller for: " + baseDn.toString() +
          " terminated.");
      }
    }
  }

  /**
   * Signals the RS we just entered a new status.
   * @param newStatus The status the local DS just entered
   */
  public void signalStatusChange(ServerStatus newStatus)
  {
    try
    {
      ChangeStatusMsg csMsg = new ChangeStatusMsg(ServerStatus.INVALID_STATUS,
        newStatus);
      session.publish(csMsg);
    } catch (IOException ex)
    {
      Message message = ERR_EXCEPTION_SENDING_CS.get(
        baseDn,
        Integer.toString(serverId),
        ex.getLocalizedMessage() + stackTraceToSingleLineString(ex));
      logError(message);
    }
  }

  /**
   * Sets the group id of the broker.
   * @param groupId The new group id.
   */
  public void setGroupId(byte groupId)
  {
    this.groupId = groupId;
  }

  /**
   * Gets the info for DSs in the topology (except us).
   * @return The info for DSs in the topology (except us)
   */
  public List<DSInfo> getDsList()
  {
    return dsList;
  }

  /**
   * Gets the info for RSs in the topology (except the one we are connected
   * to).
   * @return The info for RSs in the topology (except the one we are connected
   * to)
   */
  public List<RSInfo> getRsList()
  {
    return rsList;
  }

  /**
   * Processes an incoming TopologyMsg.
   * Updates the structures for the local view of the topology.
   *
   * @param topoMsg The topology information received from RS.
   */
  public void receiveTopo(TopologyMsg topoMsg)
  {
    // Store new lists
    synchronized(getDsList())
    {
      synchronized(getRsList())
      {
        dsList = topoMsg.getDsList();
        rsList = topoMsg.getRsList();
      }
    }
    if (domain != null)
    {
      for (DSInfo info : dsList)
      {
        for (String attr : info.getEclIncludes())
        {
          domain.addEclInclude(attr);
        }
      }
    }
  }
  /**
   * Check if the broker could not find any Replication Server and therefore
   * connection attempt failed.
   *
   * @return true if the server could not connect to any Replication Server.
   */
  public boolean hasConnectionError()
  {
    return connectionError;
  }

  /**
   * Starts publishing to the RS the current timestamp used in this server.
   */
  public void startChangeTimeHeartBeatPublishing()
  {
    // Start a CN heartbeat thread.
    if (changeTimeHeartbeatSendInterval > 0)
    {
      ctHeartbeatPublisherThread =
        new CTHeartbeatPublisherThread(
            "Replication CN Heartbeat sender for " +
            baseDn + " with " + getReplicationServer(),
            session, changeTimeHeartbeatSendInterval, serverId);
      ctHeartbeatPublisherThread.start();
    }
    else
    {
      if (debugEnabled())
        TRACER.debugInfo(this +
          " is not configured to send CN heartbeat interval");
    }
  }

  /**
   * Stops publishing to the RS the current timestamp used in this server.
   */
  public void stopChangeTimeHeartBeatPublishing()
  {
    if (ctHeartbeatPublisherThread != null)
    {
      ctHeartbeatPublisherThread.shutdown();
      ctHeartbeatPublisherThread = null;
    }
  }

  /**
   * Set the connectRequiresRecovery to the provided value.
   * This flag is used to indicate if a recovery of Update is necessary
   * after a reconnection to a RS.
   * It is the responsibility of the ReplicationDomain to set it during the
   * sessionInitiated phase.
   *
   * @param b the new value of the connectRequiresRecovery.
   */
  public void setRecoveryRequired(boolean b)
  {
    connectRequiresRecovery = b;
  }
}
