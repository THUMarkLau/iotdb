/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.server.member;

import static org.apache.iotdb.cluster.utils.ClusterUtils.WAIT_START_UP_CHECK_TIME_SEC;
import static org.apache.iotdb.cluster.utils.ClusterUtils.analyseStartUpCheckResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.iotdb.cluster.ClusterFileFlushPolicy;
import org.apache.iotdb.cluster.client.DataClientProvider;
import org.apache.iotdb.cluster.client.async.AsyncClientPool;
import org.apache.iotdb.cluster.client.async.AsyncMetaClient;
import org.apache.iotdb.cluster.client.async.AsyncMetaHeartbeatClient;
import org.apache.iotdb.cluster.client.sync.SyncClientAdaptor;
import org.apache.iotdb.cluster.client.sync.SyncClientPool;
import org.apache.iotdb.cluster.client.sync.SyncMetaClient;
import org.apache.iotdb.cluster.client.sync.SyncMetaHeartbeatClient;
import org.apache.iotdb.cluster.config.ClusterConstant;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.AddSelfException;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.exception.ConfigInconsistentException;
import org.apache.iotdb.cluster.exception.LogExecutionException;
import org.apache.iotdb.cluster.exception.PartitionTableUnavailableException;
import org.apache.iotdb.cluster.exception.SnapshotInstallationException;
import org.apache.iotdb.cluster.exception.StartUpCheckFailureException;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.log.Log;
import org.apache.iotdb.cluster.log.LogApplier;
import org.apache.iotdb.cluster.log.applier.MetaLogApplier;
import org.apache.iotdb.cluster.log.logtypes.AddNodeLog;
import org.apache.iotdb.cluster.log.logtypes.RemoveNodeLog;
import org.apache.iotdb.cluster.log.manage.MetaSingleSnapshotLogManager;
import org.apache.iotdb.cluster.log.snapshot.MetaSimpleSnapshot;
import org.apache.iotdb.cluster.metadata.CMManager;
import org.apache.iotdb.cluster.partition.NodeAdditionResult;
import org.apache.iotdb.cluster.partition.NodeRemovalResult;
import org.apache.iotdb.cluster.partition.PartitionGroup;
import org.apache.iotdb.cluster.partition.PartitionTable;
import org.apache.iotdb.cluster.partition.slot.SlotPartitionTable;
import org.apache.iotdb.cluster.query.ClusterPlanRouter;
import org.apache.iotdb.cluster.query.manage.QueryCoordinator;
import org.apache.iotdb.cluster.rpc.thrift.AddNodeResponse;
import org.apache.iotdb.cluster.rpc.thrift.AppendEntryRequest;
import org.apache.iotdb.cluster.rpc.thrift.CheckStatusResponse;
import org.apache.iotdb.cluster.rpc.thrift.HeartBeatRequest;
import org.apache.iotdb.cluster.rpc.thrift.HeartBeatResponse;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.RaftService;
import org.apache.iotdb.cluster.rpc.thrift.RaftService.Client;
import org.apache.iotdb.cluster.rpc.thrift.SendSnapshotRequest;
import org.apache.iotdb.cluster.rpc.thrift.StartUpStatus;
import org.apache.iotdb.cluster.rpc.thrift.TSMetaService;
import org.apache.iotdb.cluster.rpc.thrift.TSMetaService.AsyncClient;
import org.apache.iotdb.cluster.server.ClientServer;
import org.apache.iotdb.cluster.server.DataClusterServer;
import org.apache.iotdb.cluster.server.HardLinkCleaner;
import org.apache.iotdb.cluster.server.NodeCharacter;
import org.apache.iotdb.cluster.server.NodeReport;
import org.apache.iotdb.cluster.server.NodeReport.MetaMemberReport;
import org.apache.iotdb.cluster.server.RaftServer;
import org.apache.iotdb.cluster.server.Response;
import org.apache.iotdb.cluster.server.Timer;
import org.apache.iotdb.cluster.server.handlers.caller.AppendGroupEntryHandler;
import org.apache.iotdb.cluster.server.handlers.caller.GenericHandler;
import org.apache.iotdb.cluster.server.handlers.caller.NodeStatusHandler;
import org.apache.iotdb.cluster.server.heartbeat.DataHeartbeatServer;
import org.apache.iotdb.cluster.server.heartbeat.MetaHeartbeatThread;
import org.apache.iotdb.cluster.server.member.DataGroupMember.Factory;
import org.apache.iotdb.cluster.utils.ClientUtils;
import org.apache.iotdb.cluster.utils.ClusterUtils;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.cluster.utils.PartitionUtils.Intervals;
import org.apache.iotdb.cluster.utils.StatusUtils;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.exception.StartupException;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.qp.executor.PlanExecutor;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.DeletePlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.DeleteTimeSeriesPlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.EndPoint;
import org.apache.iotdb.service.rpc.thrift.TSStatus;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("java:S1135")
public class MetaGroupMember extends RaftMember {

  /**
   * the file that contains the identifier of this node
   */
  static final String NODE_IDENTIFIER_FILE_NAME =
      IoTDBDescriptor.getInstance().getConfig().getBaseDir() + File.separator + "node_identifier";
  /**
   * the file that contains the serialized partition table
   */
  static final String PARTITION_FILE_NAME =
      IoTDBDescriptor.getInstance().getConfig().getBaseDir() + File.separator + "partitions";
  /**
   * in case of data loss, some file changes would be made to a temporary file first
   */
  private static final String TEMP_SUFFIX = ".tmp";
  private static final String MSG_MULTIPLE_ERROR = "The following errors occurred when executing "
      + "the query, please retry or contact the DBA: ";

  private static final Logger logger = LoggerFactory.getLogger(MetaGroupMember.class);
  /**
   * when joining a cluster this node will retry at most "DEFAULT_JOIN_RETRY" times before returning
   * a failure to the client
   */
  private static final int DEFAULT_JOIN_RETRY = 10;

  /**
   * every "REPORT_INTERVAL_SEC" seconds, a reporter thread will print the status of all raft
   * members in this node
   */
  private static final int REPORT_INTERVAL_SEC = 10;
  /**
   * how many times is a data record replicated, also the number of nodes in a data group
   */
  private static final int REPLICATION_NUM =
      ClusterDescriptor.getInstance().getConfig().getReplicationNum();

  /**
   * during snapshot, hardlinks of data files are created to for downloading. hardlinks will be
   * checked every hour by default to see if they have expired, and will be cleaned if so.
   */
  private static final long CLEAN_HARDLINK_INTERVAL_SEC = 3600;

  /**
   * blind nodes are nodes that do not have the partition table, and if this node is the leader, the
   * partition table should be sent to them at the next heartbeat
   */
  private Set<Node> blindNodes = new HashSet<>();
  /**
   * as a leader, when a follower sent this node its identifier, the identifier may conflict with
   * other nodes', such conflicting nodes will be recorded and at the next heartbeat, and they will
   * be required to regenerate an identifier.
   */
  private Set<Node> idConflictNodes = new HashSet<>();
  /**
   * the identifier and its belonging node, for conflict detection, may be used in more places in
   * the future
   */
  private Map<Integer, Node> idNodeMap = null;

  /**
   * nodes in the cluster and data partitioning
   */
  private PartitionTable partitionTable;
  /**
   * router calculates the partition groups that a partitioned plan should be sent to
   */
  private ClusterPlanRouter router;
  /**
   * each node contains multiple DataGroupMembers and they are managed by a DataClusterServer acting
   * as a broker
   */
  private DataClusterServer dataClusterServer;

  /**
   * each node starts a data heartbeat server to transfer heartbeat requests
   */
  private DataHeartbeatServer dataHeartbeatServer;

  /**
   * an override of TSServiceImpl, which redirect JDBC and Session requests to the MetaGroupMember
   * so they can be processed cluster-wide
   */
  private ClientServer clientServer;

  private DataClientProvider dataClientProvider;

  /**
   * a single thread pool, every "REPORT_INTERVAL_SEC" seconds, "reportThread" will print the status
   * of all raft members in this node
   */
  private ScheduledExecutorService reportThread;

  /**
   * containing configurations that should be kept the same cluster-wide, and must be checked before
   * establishing a cluster or joining a cluster.
   */
  private StartUpStatus startUpStatus;

  /**
   * localExecutor is used to directly execute plans like load configuration in the underlying
   * IoTDB
   */
  private PlanExecutor localExecutor;

  /**
   * hardLinkCleaner will periodically clean expired hardlinks created during snapshots
   */
  private ScheduledExecutorService hardLinkCleanerThread;

  @TestOnly
  public MetaGroupMember() {
  }

  public MetaGroupMember(TProtocolFactory factory, Node thisNode) throws QueryProcessException {
    super("Meta", new AsyncClientPool(new AsyncMetaClient.FactoryAsync(factory)),
        new SyncClientPool(new SyncMetaClient.FactorySync(factory)),
        new AsyncClientPool(new AsyncMetaHeartbeatClient.FactoryAsync(factory)),
        new SyncClientPool(new SyncMetaHeartbeatClient.FactorySync(factory)));
    allNodes = new ArrayList<>();
    initPeerMap();

    dataClientProvider = new DataClientProvider(factory);

    // committed logs are applied to the state machine (the IoTDB instance) through the applier
    LogApplier metaLogApplier = new MetaLogApplier(this);
    logManager = new MetaSingleSnapshotLogManager(metaLogApplier, this);
    term.set(logManager.getHardState().getCurrentTerm());
    voteFor = logManager.getHardState().getVoteFor();

    setThisNode(thisNode);
    // load the identifier from the disk or generate a new one
    loadIdentifier();
    allNodes.add(thisNode);

    Factory dataMemberFactory = new Factory(factory, this);
    dataClusterServer = new DataClusterServer(thisNode, dataMemberFactory, this);
    dataHeartbeatServer = new DataHeartbeatServer(thisNode, dataClusterServer);
    clientServer = new ClientServer(this);
    startUpStatus = getNewStartUpStatus();
  }

  /**
   * Find the DataGroupMember that manages the partition of "storageGroupName"@"partitionId", and
   * close the partition through that member. Notice: only partitions owned by this node can be
   * closed by the method.
   *
   * @return true if the member is a leader and the partition is closed, false otherwise
   */
  public void closePartition(String storageGroupName, long partitionId, boolean isSeq) {
    Node header = partitionTable.routeToHeaderByTime(storageGroupName,
        partitionId * StorageEngine.getTimePartitionInterval());
    DataGroupMember localDataMember = getLocalDataMember(header);
    if (localDataMember == null || localDataMember.getCharacter() != NodeCharacter.LEADER) {
      return;
    }
    localDataMember.closePartition(storageGroupName, partitionId, isSeq);
  }

  public DataClusterServer getDataClusterServer() {
    return dataClusterServer;
  }

  public DataHeartbeatServer getDataHeartbeatServer() {
    return dataHeartbeatServer;
  }

  /**
   * Add seed nodes from the config, start the heartbeat and catch-up thread pool, initialize
   * QueryCoordinator and FileFlushPolicy, then start the reportThread. Calling the method twice
   * does not induce side effect.
   */
  @Override
  public void start() {
    if (heartBeatService != null) {
      return;
    }
    addSeedNodes();
    super.start();
    QueryCoordinator.getINSTANCE().setMetaGroupMember(this);
    StorageEngine.getInstance().setFileFlushPolicy(new ClusterFileFlushPolicy(this));
    reportThread = Executors.newSingleThreadScheduledExecutor(n -> new Thread(n,
        "NodeReportThread"));
    hardLinkCleanerThread = Executors.newSingleThreadScheduledExecutor(n -> new Thread(n,
        "HardLinkCleaner"));
  }

  /**
   * Stop the heartbeat and catch-up thread pool, DataClusterServer, ClientServer and reportThread.
   * Calling the method twice does not induce side effects.
   */
  @Override
  public void stop() {
    super.stop();
    if (getDataClusterServer() != null) {
      getDataClusterServer().stop();
    }
    if (getDataHeartbeatServer() != null) {
      getDataHeartbeatServer().stop();
    }
    if (clientServer != null) {
      clientServer.stop();
    }
    if (reportThread != null) {
      reportThread.shutdownNow();
      try {
        reportThread.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.error("Unexpected interruption when waiting for reportThread to end", e);
      }
    }
    if (hardLinkCleanerThread != null) {
      hardLinkCleanerThread.shutdownNow();
      try {
        hardLinkCleanerThread.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.error("Unexpected interruption when waiting for hardlinkCleaner to end", e);
      }
    }

    logger.info("{}: stopped", name);
  }

  /**
   * Start DataClusterServer and ClientServer so this node will be able to respond to other nodes
   * and clients.
   */
  private void initSubServers() throws TTransportException, StartupException {
    getDataClusterServer().start();
    getDataHeartbeatServer().start();
    clientServer.start();
  }

  /**
   * Parse the seed nodes from the cluster configuration and add them into the node list. Each
   * seedUrl should be like "{hostName}:{metaPort}:{dataPort}" Ignore bad-formatted seedUrls.
   */
  protected void addSeedNodes() {
    List<String> seedUrls = config.getSeedNodeUrls();
    // initialize allNodes
    for (String seedUrl : seedUrls) {
      Node node = ClusterUtils.parseNode(seedUrl);
      if (node != null && (!node.getIp().equals(thisNode.ip) || node.getMetaPort() != thisNode
          .getMetaPort()) && !allNodes.contains(node)) {
        // do not add the local node since it is added in the constructor
        allNodes.add(node);
      }
    }
  }

  /**
   * Apply the addition of a new node. Register its identifier, add it to the node list and
   * partition table, serialize the partition table and update the DataGroupMembers.
   */
  public void applyAddNode(Node newNode) {
    synchronized (allNodes) {
      if (!allNodes.contains(newNode)) {
        logger.debug("Adding a new node {} into {}", newNode, allNodes);
        registerNodeIdentifier(newNode, newNode.getNodeIdentifier());
        allNodes.add(newNode);

        // update the partition table
        NodeAdditionResult result = partitionTable.addNode(newNode);
        savePartitionTable();

        // update local data members
        getDataClusterServer().addNode(newNode, result);
      }
    }
  }

  /**
   * This node itself is a seed node, and it is going to build the initial cluster with other seed
   * nodes. This method is to skip one-by-one additions to establish a large cluster quickly.
   */
  public void buildCluster() throws ConfigInconsistentException, StartUpCheckFailureException {
    // see if the seed nodes have consistent configurations
    checkSeedNodesStatus();
    // try loading the partition table if there was a previous cluster
    loadPartitionTable();
    // just establish the heartbeat thread and it will do the remaining
    threadTaskInit();
    if (allNodes.size() == 1) {
      // if there is only one node in the cluster, no heartbeat will be received, and
      // consequently data group will not be built, so we directly build data members here
      if (partitionTable == null) {
        partitionTable = new SlotPartitionTable(allNodes, thisNode);
        logger.info("Partition table is set up");
      }
      router = new ClusterPlanRouter(partitionTable);
      startSubServers();
    }
  }

  private void threadTaskInit() {
    heartBeatService.submit(new MetaHeartbeatThread(this));
    reportThread.scheduleAtFixedRate(() -> logger.info(genNodeReport().toString()),
        REPORT_INTERVAL_SEC, REPORT_INTERVAL_SEC, TimeUnit.SECONDS);
    hardLinkCleanerThread.scheduleAtFixedRate(new HardLinkCleaner(),
        CLEAN_HARDLINK_INTERVAL_SEC, CLEAN_HARDLINK_INTERVAL_SEC, TimeUnit.SECONDS);
  }

  /**
   * This node is not a seed node and wants to join an established cluster. Pick up a node randomly
   * from the seed nodes and send a join request to it.
   *
   * @return true if the node has successfully joined the cluster, false otherwise.
   */
  public void joinCluster() throws ConfigInconsistentException, StartUpCheckFailureException {
    if (allNodes.size() == 1) {
      logger.error("Seed nodes not provided, cannot join cluster");
      throw new ConfigInconsistentException();
    }

    int retry = DEFAULT_JOIN_RETRY;
    while (retry > 0) {
      // randomly pick up a node to try
      Node node = allNodes.get(random.nextInt(allNodes.size()));
      if (node.equals(thisNode)) {
        continue;
      }
      logger.info("start joining the cluster with the help of {}", node);
      try {
        if (joinCluster(node, startUpStatus)) {
          logger.info("Joined a cluster, starting the heartbeat thread");
          setCharacter(NodeCharacter.FOLLOWER);
          setLastHeartbeatReceivedTime(System.currentTimeMillis());
          threadTaskInit();
          return;
        }
        // wait 5s to start the next try
        Thread.sleep(5000);
      } catch (TException e) {
        logger.warn("Cannot join the cluster from {}, because:", node, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Unexpected interruption when waiting to join a cluster", e);
      }
      // start the next try
      retry--;
    }
    // all tries failed
    logger.error("Cannot join the cluster after {} retries", DEFAULT_JOIN_RETRY);
    throw new StartUpCheckFailureException();
  }


  public StartUpStatus getNewStartUpStatus() {
    StartUpStatus newStartUpStatus = new StartUpStatus();
    newStartUpStatus
        .setPartitionInterval(IoTDBDescriptor.getInstance().getConfig().getPartitionInterval());
    newStartUpStatus.setHashSalt(ClusterConstant.HASH_SALT);
    newStartUpStatus
        .setReplicationNumber(ClusterDescriptor.getInstance().getConfig().getReplicationNum());
    newStartUpStatus.setClusterName(ClusterDescriptor.getInstance().getConfig().getClusterName());
    List<String> seedUrls = ClusterDescriptor.getInstance().getConfig().getSeedNodeUrls();
    List<Node> seedNodeList = new ArrayList<>();
    for (String seedUrl : seedUrls) {
      seedNodeList.add(ClusterUtils.parseNode(seedUrl));
    }
    newStartUpStatus.setSeedNodeList(seedNodeList);
    return newStartUpStatus;
  }

  /**
   * Send a join cluster request to "node". If the joining is accepted, set the partition table,
   * start DataClusterServer and ClientServer and initialize DataGroupMembers.
   *
   * @return rue if the node has successfully joined the cluster, false otherwise.
   */
  private boolean joinCluster(Node node, StartUpStatus startUpStatus)
      throws TException, InterruptedException, ConfigInconsistentException {

    AddNodeResponse resp;
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncMetaClient client = (AsyncMetaClient) getAsyncClient(node);
      resp = SyncClientAdaptor.addNode(client, thisNode, startUpStatus);
    } else {
      SyncMetaClient client = (SyncMetaClient) getSyncClient(node);
      try {
        resp = client.addNode(thisNode, startUpStatus);
      } finally {
        ClientUtils.putBackSyncClient(client);
      }
    }

    if (resp == null) {
      logger.warn("Join cluster request timed out");
    } else if (resp.getRespNum() == Response.RESPONSE_AGREE) {
      logger.info("Node {} admitted this node into the cluster", node);
      ByteBuffer partitionTableBuffer = resp.partitionTableBytes;
      acceptPartitionTable(partitionTableBuffer);
      getDataClusterServer().pullSnapshots();
      return true;
    } else if (resp.getRespNum() == Response.RESPONSE_IDENTIFIER_CONFLICT) {
      logger.info("The identifier {} conflicts the existing ones, regenerate a new one",
          thisNode.getNodeIdentifier());
      setNodeIdentifier(genNodeIdentifier());
    } else if (resp.getRespNum() == Response.RESPONSE_NEW_NODE_PARAMETER_CONFLICT) {
      handleConfigInconsistency(resp);
    } else {
      logger
          .warn("Joining the cluster is rejected by {} for response {}", node, resp.getRespNum());
    }
    return false;
  }

  private void handleConfigInconsistency(AddNodeResponse resp) throws ConfigInconsistentException {
    if (logger.isInfoEnabled()) {
      CheckStatusResponse checkStatusResponse = resp.getCheckStatusResponse();
      String parameters =
          (checkStatusResponse.isPartitionalIntervalEquals() ? "" : ", partition interval")
              + (checkStatusResponse.isHashSaltEquals() ? "" : ", hash salt")
              + (checkStatusResponse.isReplicationNumEquals() ? "" : ", replication number")
              + (checkStatusResponse.isSeedNodeEquals() ? "" : ", seedNodes")
              + (checkStatusResponse.isClusterNameEquals() ? "" : ", clusterName");
      logger.error(
          "The start up configuration{} conflicts the cluster. Please reset the configurations. ",
          parameters.substring(1));
    }
    throw new ConfigInconsistentException();
  }

  /**
   * Process the heartbeat request from a valid leader. Generate and tell the leader the identifier
   * of the node if necessary. If the partition table is missing, use the one from the request or
   * require it in the response.
   */
  @Override
  void processValidHeartbeatReq(HeartBeatRequest request, HeartBeatResponse response) {
    if (request.isRequireIdentifier()) {
      // the leader wants to know who the node is
      if (request.isRegenerateIdentifier()) {
        // the previously sent id conflicted, generate a new one
        setNodeIdentifier(genNodeIdentifier());
      }
      logger.debug("Send identifier {} to the leader", thisNode.getNodeIdentifier());
      response.setFollowerIdentifier(thisNode.getNodeIdentifier());
    }

    if (partitionTable == null) {
      // this node does not have a partition table yet
      if (request.isSetPartitionTableBytes()) {
        synchronized (this) {
          // if the leader has sent the partition table then accept it
          if (partitionTable == null) {
            ByteBuffer byteBuffer = request.partitionTableBytes;
            acceptPartitionTable(byteBuffer);
          }
        }
      } else {
        // require the partition table
        logger.debug("Request cluster nodes from the leader");
        response.setRequirePartitionTable(true);
      }
    }
  }

  /**
   * Deserialize a partition table from the buffer, save it locally, add nodes from the partition
   * table and start DataClusterServer and ClientServer.
   */
  public void acceptPartitionTable(ByteBuffer partitionTableBuffer) {
    partitionTable = new SlotPartitionTable(thisNode);
    partitionTable.deserialize(partitionTableBuffer);

    savePartitionTable();
    router = new ClusterPlanRouter(partitionTable);

    allNodes = new ArrayList<>(partitionTable.getAllNodes());
    initPeerMap();
    logger.info("Received cluster nodes from the leader: {}", allNodes);
    initIdNodeMap();
    for (Node n : allNodes) {
      idNodeMap.put(n.getNodeIdentifier(), n);
    }
    try {
      syncLeaderWithConsistencyCheck();
    } catch (CheckConsistencyException e) {
      logger.error("check consistency failed when accept partition table: {}", e.getMessage());
    }

    startSubServers();
  }

  /**
   * Process a HeartBeatResponse from a follower. If the follower has provided its identifier, try
   * registering for it and if all nodes have registered and there is no available partition table,
   * initialize a new one and start the ClientServer and DataClusterServer. If the follower requires
   * a partition table, add it to the blind node list so that at the next heartbeat this node will
   * send it a partition table
   */
  @Override
  public void processValidHeartbeatResp(HeartBeatResponse response, Node receiver) {
    // register the id of the node
    if (response.isSetFollowerIdentifier()) {
      registerNodeIdentifier(receiver, response.getFollowerIdentifier());
      // if all nodes' ids are known, we can build the partition table
      if (allNodesIdKnown()) {
        if (partitionTable == null) {
          partitionTable = new SlotPartitionTable(allNodes, thisNode);
          logger.info("Partition table is set up");
        }
        router = new ClusterPlanRouter(partitionTable);
        startSubServers();
      }
    }
    // record the requirement of partition table of the follower
    if (response.isRequirePartitionTable()) {
      addBlindNode(receiver);
    }
  }

  /**
   * When a node requires a partition table in its heartbeat response, add it into blindNodes so in
   * the next heartbeat the partition table will be sent to the node.
   */
  private void addBlindNode(Node node) {
    logger.debug("Node {} requires the node list", node);
    blindNodes.add(node);
  }

  /**
   * @return whether a node wants the partition table.
   */
  public boolean isNodeBlind(Node node) {
    return blindNodes.contains(node);
  }

  /**
   * Remove the node from the blindNodes when the partition table is sent, so partition table will
   * not be sent in each heartbeat.
   */
  public void removeBlindNode(Node node) {
    blindNodes.remove(node);
  }

  /**
   * Register the identifier for the node if it does not conflict with other nodes.
   */
  private void registerNodeIdentifier(Node node, int identifier) {
    synchronized (idNodeMap) {
      Node conflictNode = idNodeMap.get(identifier);
      if (conflictNode != null && !conflictNode.equals(node)) {
        idConflictNodes.add(node);
        return;
      }
      node.setNodeIdentifier(identifier);
      logger.info("Node {} registered with id {}", node, identifier);
      idNodeMap.put(identifier, node);
      idConflictNodes.remove(node);
    }
  }

  /**
   * idNodeMap is initialized when the first leader wins or the follower receives the partition
   * table from the leader or a node recovers
   */
  private void initIdNodeMap() {
    idNodeMap = new HashMap<>();
    idNodeMap.put(thisNode.getNodeIdentifier(), thisNode);
  }


  /**
   * @return Whether all nodes' identifier is known.
   */
  private boolean allNodesIdKnown() {
    return idNodeMap != null && idNodeMap.size() == allNodes.size();
  }

  /**
   * Start the DataClusterServer and ClientServer so this node can serve other nodes and clients.
   * Also build DataGroupMembers using the partition table.
   */
  private synchronized void startSubServers() {
    logger.info("Starting sub-servers...");
    synchronized (partitionTable) {
      try {
        initSubServers();
        getDataClusterServer().buildDataGroupMembers(partitionTable);
      } catch (TTransportException | StartupException e) {
        logger.error("Build partition table failed: ", e);
        stop();
        return;
      }
    }
    logger.info("Sub-servers started.");
  }

  /**
   * Process the join cluster request of "node". Only proceed when the partition table is ready.
   *
   * @param node cannot be the local node
   */
  public AddNodeResponse addNode(Node node, StartUpStatus startUpStatus)
      throws AddSelfException, LogExecutionException {
    AddNodeResponse response = new AddNodeResponse();
    if (partitionTable == null) {
      logger.info("Cannot add node now because the partition table is not set");
      response.setRespNum((int) Response.RESPONSE_PARTITION_TABLE_UNAVAILABLE);
      return response;
    }

    logger.info("A node {} wants to join this cluster", node);
    if (node.equals(thisNode)) {
      throw new AddSelfException();
    }

    waitLeader();
    // try to process the request locally
    if (processAddNodeLocally(node, startUpStatus, response)) {
      return response;
    }
    // if it cannot be processed locally, forward it
    return null;
  }

  /**
   * Process the join cluster request of "node" as a MetaLeader. A node already joined is accepted
   * immediately. If the identifier of "node" conflicts with an existing node, the request will be
   * turned down.
   *
   * @param node cannot be the local node
   * @param startUpStatus the start up status of the new node
   * @param response the response that will be sent to "node"
   * @return true if the process is over, false if the request should be forwarded
   */
  private boolean processAddNodeLocally(Node node, StartUpStatus startUpStatus,
      AddNodeResponse response) throws LogExecutionException {
    if (character != NodeCharacter.LEADER) {
      return false;
    }
    if (allNodes.contains(node)) {
      logger.debug("Node {} is already in the cluster", node);
      response.setRespNum((int) Response.RESPONSE_AGREE);
      synchronized (partitionTable) {
        response.setPartitionTableBytes(partitionTable.serialize());
      }
      return true;
    }

    Node idConflictNode = idNodeMap.get(node.getNodeIdentifier());
    if (idConflictNode != null) {
      logger.debug("{}'s id conflicts with {}", node, idConflictNode);
      response.setRespNum((int) Response.RESPONSE_IDENTIFIER_CONFLICT);
      return true;
    }

    // check status of the new node
    if (!checkNodeConfig(startUpStatus, response)) {
      return true;
    }

    // node adding is serialized to reduce potential concurrency problem
    synchronized (logManager) {
      AddNodeLog addNodeLog = new AddNodeLog();
      addNodeLog.setCurrLogTerm(getTerm().get());
      addNodeLog.setCurrLogIndex(logManager.getLastLogIndex() + 1);

      addNodeLog.setNewNode(node);

      logManager.append(addNodeLog);

      int retryTime = 1;
      while (true) {
        logger
            .info("Send the join request of {} to other nodes, retry time: {}", node, retryTime);
        AppendLogResult result = sendLogToAllGroups(addNodeLog);
        switch (result) {
          case OK:
            logger.info("Join request of {} is accepted", node);
            commitLog(addNodeLog);

            synchronized (partitionTable) {
              response.setPartitionTableBytes(partitionTable.serialize());
            }
            response.setRespNum((int) Response.RESPONSE_AGREE);
            logger.info("Sending join response of {}", node);
            return true;
          case TIME_OUT:
            logger.info("Join request of {} timed out", node);
            retryTime++;
            continue;
          case LEADERSHIP_STALE:
          default:
            return false;
        }
      }
    }
  }

  private boolean checkNodeConfig(StartUpStatus remoteStartUpStatus, AddNodeResponse response) {
    long remotePartitionInterval = remoteStartUpStatus.getPartitionInterval();
    int remoteHashSalt = remoteStartUpStatus.getHashSalt();
    int remoteReplicationNum = remoteStartUpStatus.getReplicationNumber();
    String remoteClusterName = remoteStartUpStatus.getClusterName();
    List<Node> remoteSeedNodeList = remoteStartUpStatus.getSeedNodeList();
    long localPartitionInterval = IoTDBDescriptor.getInstance().getConfig()
        .getPartitionInterval();
    int localHashSalt = ClusterConstant.HASH_SALT;
    int localReplicationNum = ClusterDescriptor.getInstance().getConfig().getReplicationNum();
    String localClusterName = ClusterDescriptor.getInstance().getConfig().getClusterName();
    boolean partitionIntervalEquals = true;
    boolean hashSaltEquals = true;
    boolean replicationNumEquals = true;
    boolean seedNodeEquals = true;
    boolean clusterNameEquals = true;

    if (localPartitionInterval != remotePartitionInterval) {
      partitionIntervalEquals = false;
      logger.info("Remote partition interval conflicts with the leader's. Leader: {}, remote: {}",
          localPartitionInterval, remotePartitionInterval);
    }
    if (localHashSalt != remoteHashSalt) {
      hashSaltEquals = false;
      logger.info("Remote hash salt conflicts with the leader's. Leader: {}, remote: {}",
          localHashSalt, remoteHashSalt);
    }
    if (localReplicationNum != remoteReplicationNum) {
      replicationNumEquals = false;
      logger.info("Remote replication number conflicts with the leader's. Leader: {}, remote: {}",
          localReplicationNum, remoteReplicationNum);
    }
    if (!Objects.equals(localClusterName, remoteClusterName)) {
      clusterNameEquals = false;
      logger.info("Remote cluster name conflicts with the leader's. Leader: {}, remote: {}",
          localClusterName, remoteClusterName);
    }
    if (!ClusterUtils.checkSeedNodes(true, allNodes, remoteSeedNodeList)) {
      seedNodeEquals = false;
      if (logger.isInfoEnabled()) {
        logger.info("Remote seed node list conflicts with the leader's. Leader: {}, remote: {}",
            Arrays.toString(allNodes.toArray(new Node[0])), remoteSeedNodeList);
      }
    }
    if (!(partitionIntervalEquals && hashSaltEquals && replicationNumEquals && seedNodeEquals
        && clusterNameEquals)) {
      response.setRespNum((int) Response.RESPONSE_NEW_NODE_PARAMETER_CONFLICT);
      response.setCheckStatusResponse(
          new CheckStatusResponse(partitionIntervalEquals, hashSaltEquals,
              replicationNumEquals, seedNodeEquals, clusterNameEquals));
      return false;
    }
    return true;
  }

  /**
   * Check if the seed nodes are consistent with other nodes. Only used when establishing the
   * initial cluster.
   */
  private void checkSeedNodesStatus()
      throws ConfigInconsistentException, StartUpCheckFailureException {
    if (getAllNodes().size() == 1) {
      // one-node cluster, skip the check
      return;
    }

    boolean canEstablishCluster = false;
    long startTime = System.currentTimeMillis();
    // the initial 1 represents this node
    AtomicInteger consistentNum = new AtomicInteger(1);
    AtomicInteger inconsistentNum = new AtomicInteger(0);
    while (!canEstablishCluster) {
      consistentNum.set(1);
      inconsistentNum.set(0);
      checkSeedNodesStatusOnce(consistentNum, inconsistentNum);
      canEstablishCluster = analyseStartUpCheckResult(consistentNum.get(), inconsistentNum.get(),
          getAllNodes().size());
      // If reach the start up time threshold, shut down.
      // Otherwise, wait for a while, start the loop again.
      if (System.currentTimeMillis() - startTime > ClusterUtils.START_UP_TIME_THRESHOLD_MS) {
        throw new StartUpCheckFailureException();
      } else if (!canEstablishCluster) {
        try {
          Thread.sleep(ClusterUtils.START_UP_CHECK_TIME_INTERVAL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.error("Unexpected interruption when waiting for next start up check", e);
        }
      }
    }
  }

  private void checkSeedNodesStatusOnce(AtomicInteger consistentNum,
      AtomicInteger inconsistentNum) {
    // use a thread pool to avoid being blocked by an unavailable node
    ExecutorService pool = new ScheduledThreadPoolExecutor(getAllNodes().size() - 1);
    for (Node seedNode : getAllNodes()) {
      Node thisNode = getThisNode();
      if (seedNode.equals(thisNode)) {
        continue;
      }
      pool.submit(() -> {
            CheckStatusResponse response = checkStatus(seedNode);
            if (response != null) {
              // check the response
              ClusterUtils
                  .examineCheckStatusResponse(response, consistentNum, inconsistentNum, seedNode);
            } else {
              logger.warn(
                  "Start up exception. Cannot connect to node {}. Try again in next turn.",
                  seedNode);
            }
          }
      );
    }
    pool.shutdown();
    try {
      if (!pool.awaitTermination(WAIT_START_UP_CHECK_TIME_SEC, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Unexpected interruption when waiting for start up checks", e);
    }
  }

  private CheckStatusResponse checkStatus(Node seedNode) {
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncMetaClient client = (AsyncMetaClient) getAsyncClient(seedNode);
      try {
        return SyncClientAdaptor.checkStatus(client, getStartUpStatus());
      } catch (TException e) {
        logger.warn("Error occurs when check status on node : {}", seedNode);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Current thread is interrupted.");
      }
    } else {
      SyncMetaClient client = (SyncMetaClient) getSyncClient(seedNode);
      try {
        return client.checkStatus(getStartUpStatus());
      } catch (TException e) {
        logger.warn("Error occurs when check status on node : {}", seedNode);
      } finally {
        ClientUtils.putBackSyncClient(client);
      }
    }
    return null;
  }

  /**
   * Send the log the all data groups and return a success only when each group's quorum has
   * accepted this log.
   */
  private AppendLogResult sendLogToAllGroups(Log log) {
    List<Node> nodeRing = partitionTable.getAllNodes();

    AtomicLong newLeaderTerm = new AtomicLong(term.get());
    AtomicBoolean leaderShipStale = new AtomicBoolean(false);
    AppendEntryRequest request = buildAppendEntryRequest(log);

    // ask for votes from each node
    int[] groupRemainings = askGroupVotes(nodeRing, request, leaderShipStale, log, newLeaderTerm);

    if (!leaderShipStale.get()) {
      // if all quorums of all groups have received this log, it is considered succeeded.
      for (int remaining : groupRemainings) {
        if (remaining > 0) {
          return AppendLogResult.TIME_OUT;
        }
      }
    } else {
      return AppendLogResult.LEADERSHIP_STALE;
    }

    return AppendLogResult.OK;
  }

  /**
   * Send "request" to each node in "nodeRing" and when a node returns a success, decrease all
   * counters of the groups it is in of "groupRemainings"
   *
   * @return a int array indicating how many votes are left in each group to make an agreement
   */
  @SuppressWarnings({"java:S2445", "java:S2274"})
  // groupRemaining is shared with the handlers,
  // and we do not wait infinitely to enable timeouts
  private int[] askGroupVotes(List<Node> nodeRing,
      AppendEntryRequest request, AtomicBoolean leaderShipStale, Log log,
      AtomicLong newLeaderTerm) {
    // each node will be the header of a group, we use the node to represent the group
    int nodeSize = nodeRing.size();
    // the decreasing counters of how many nodes in a group has received the log, each time a
    // node receive the log, the counters of all groups it is in will decrease by 1
    int[] groupRemainings = new int[nodeSize];
    // a group is considered successfully received the log if such members receive the log
    int groupQuorum = REPLICATION_NUM / 2 + 1;
    Arrays.fill(groupRemainings, groupQuorum);

    synchronized (groupRemainings) {
      // ask a vote from every node
      for (int i = 0; i < nodeSize; i++) {
        Node node = nodeRing.get(i);
        if (node.equals(thisNode)) {
          // this node automatically gives an agreement, decrease counters of all groups the local
          // node is in
          for (int j = 0; j < REPLICATION_NUM; j++) {
            int groupIndex = i - j;
            if (groupIndex < 0) {
              groupIndex += groupRemainings.length;
            }
            groupRemainings[groupIndex]--;
          }
        } else {
          askRemoteGroupVote(node, groupRemainings, i, leaderShipStale, log, newLeaderTerm,
              request);
        }
      }

      try {
        groupRemainings.wait(RaftServer.getWriteOperationTimeoutMS());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.error("Unexpected interruption when waiting for the group votes", e);
      }
    }
    return groupRemainings;
  }

  private void askRemoteGroupVote(Node node, int[] groupRemainings, int nodeIndex,
      AtomicBoolean leaderShipStale, Log log,
      AtomicLong newLeaderTerm, AppendEntryRequest request) {
    AppendGroupEntryHandler handler = new AppendGroupEntryHandler(groupRemainings,
        nodeIndex, node, leaderShipStale, log, newLeaderTerm, this);
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncMetaClient client = (AsyncMetaClient) getAsyncClient(node);
      try {
        client.appendEntry(request, handler);
      } catch (TException e) {
        logger.error("Cannot send log to node {}", node, e);
      }
    } else {
      SyncMetaClient client = (SyncMetaClient) getSyncClient(node);
      getSerialToParallelPool().submit(() -> {
        try {
          handler.onComplete(client.appendEntry(request));
        } catch (TException e) {
          handler.onError(e);
        } finally {
          ClientUtils.putBackSyncClient(client);
        }
      });
    }

  }


  public Set<Node> getIdConflictNodes() {
    return idConflictNodes;
  }

  /**
   * When this node becomes the MetaLeader (for the first time), it should init the idNodeMap, so
   * that if can require identifiers from all nodes and check if there are conflicts.
   */
  @Override
  public void onElectionWins() {
    if (idNodeMap == null) {
      initIdNodeMap();
    }
  }

  /**
   * Load the partition table from a local file if it can be found.
   */
  private void loadPartitionTable() {
    File partitionFile = new File(PARTITION_FILE_NAME);
    if (!partitionFile.exists() && !recoverPartitionTableFile()) {
      logger.info("No partition table file found");
      return;
    }
    initIdNodeMap();
    try (DataInputStream inputStream =
        new DataInputStream(new BufferedInputStream(new FileInputStream(partitionFile)))) {
      int size = inputStream.readInt();
      byte[] tableBuffer = new byte[size];
      int readCnt = inputStream.read(tableBuffer);
      if (readCnt < size) {
        throw new IOException(String.format("Expected partition table size: %s, actual read: %s",
            size, readCnt));
      }

      partitionTable = new SlotPartitionTable(thisNode);
      partitionTable.deserialize(ByteBuffer.wrap(tableBuffer));
      allNodes = new ArrayList<>(partitionTable.getAllNodes());
      initPeerMap();
      for (Node node : allNodes) {
        idNodeMap.put(node.getNodeIdentifier(), node);
      }
      router = new ClusterPlanRouter(partitionTable);
      startSubServers();

      logger.info("Load {} nodes: {}", allNodes.size(), allNodes);
    } catch (IOException e) {
      logger.error("Cannot load the partition table", e);
    }
  }

  private boolean recoverPartitionTableFile() {
    File tempFile = new File(PARTITION_FILE_NAME + TEMP_SUFFIX);
    if (!tempFile.exists()) {
      return false;
    }
    File partitionFile = new File(PARTITION_FILE_NAME);
    return tempFile.renameTo(partitionFile);
  }

  /**
   * Serialize the partition table to a fixed position on the disk. Will first serialize to a
   * temporary file and than replace the old file.
   */
  private synchronized void savePartitionTable() {
    File tempFile = new File(PARTITION_FILE_NAME + TEMP_SUFFIX);
    tempFile.getParentFile().mkdirs();
    File oldFile = new File(PARTITION_FILE_NAME);
    try (DataOutputStream outputStream =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
      synchronized (partitionTable) {
        byte[] tableBuffer = partitionTable.serialize().array();
        outputStream.writeInt(tableBuffer.length);
        outputStream.write(tableBuffer);
        outputStream.flush();
      }
    } catch (IOException e) {
      logger.error("Cannot save the partition table", e);
    }
    if (oldFile.exists()) {
      try {
        Files.delete(Paths.get(oldFile.getAbsolutePath()));
      } catch (IOException e) {
        logger.warn("Old partition table file is not successfully deleted", e);
      }
    }

    if (!tempFile.renameTo(oldFile)) {
      logger.warn("New partition table file is not successfully renamed");
    }
    logger.info("Partition table is saved");
  }

  /**
   * Load the identifier from the disk, if the identifier file does not exist, a new identifier will
   * be generated. Do nothing if the identifier is already set.
   */
  private void loadIdentifier() {
    if (thisNode.isSetNodeIdentifier()) {
      return;
    }
    File file = new File(NODE_IDENTIFIER_FILE_NAME);
    Integer nodeId = null;
    if (file.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        nodeId = Integer.parseInt(reader.readLine());
        logger.info("Recovered node identifier {}", nodeId);
      } catch (Exception e) {
        logger.warn("Cannot read the identifier from file, generating a new one", e);
      }
    }
    if (nodeId != null) {
      setNodeIdentifier(nodeId);
      return;
    }

    setNodeIdentifier(genNodeIdentifier());
  }

  /**
   * Generate a new identifier using the hash of IP, metaPort and sysTime.
   *
   * @return a new identifier
   */
  private int genNodeIdentifier() {
    return Objects.hash(thisNode.getIp(), thisNode.getMetaPort(),
        System.currentTimeMillis());
  }

  /**
   * Set the node's identifier to "identifier", also save it to a local file in text format.
   */
  private void setNodeIdentifier(int identifier) {
    logger.info("The identifier of this node has been set to {}", identifier);
    thisNode.setNodeIdentifier(identifier);
    File idFile = new File(NODE_IDENTIFIER_FILE_NAME);
    idFile.getParentFile().mkdirs();
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(idFile))) {
      writer.write(String.valueOf(identifier));
    } catch (IOException e) {
      logger.error("Cannot save the node identifier", e);
    }
  }


  public PartitionTable getPartitionTable() {
    return partitionTable;
  }

  /**
   * Process a snapshot sent by the MetaLeader. Deserialize the snapshot and apply it. The type of
   * the snapshot should be MetaSimpleSnapshot.
   */
  public void receiveSnapshot(SendSnapshotRequest request) throws SnapshotInstallationException {
    MetaSimpleSnapshot snapshot = new MetaSimpleSnapshot();
    snapshot.deserialize(request.snapshotBytes);
    snapshot.getDefaultInstaller(this).install(snapshot, -1);
  }

  /**
   * Execute a non-query plan. According to the type of the plan, the plan will be executed on all
   * nodes (like timeseries deletion) or the nodes that belong to certain groups (like data
   * ingestion).
   *
   * @param plan a non-query plan.
   */
  @Override
  public TSStatus executeNonQueryPlan(PhysicalPlan plan) {
    TSStatus result;
    long start;
    if (Timer.ENABLE_INSTRUMENTING) {
      start = System.nanoTime();
    }
    if (PartitionUtils.isLocalNonQueryPlan(plan)) { // run locally
      result = executeNonQueryLocally(plan);
    } else if (PartitionUtils.isGlobalMetaPlan(plan)) { //forward the plan to all meta group nodes
      result = processNonPartitionedMetaPlan(plan);
    } else if (PartitionUtils.isGlobalDataPlan(plan)) { //forward the plan to all data group nodes
      result = processNonPartitionedDataPlan(plan);
    } else { //split the plan and forward them to some PartitionGroups
      try {
        result = processPartitionedPlan(plan);
      } catch (UnsupportedPlanException e) {
        return StatusUtils.getStatus(StatusUtils.UNSUPPORTED_OPERATION, e.getMessage());
      }
    }
    Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY.addNanoFromStart(start);
    return result;
  }

  /**
   * execute a non-query plan that is not necessary to be executed on other nodes.
   */
  private TSStatus executeNonQueryLocally(PhysicalPlan plan) {
    boolean execRet;
    try {
      execRet = getLocalExecutor().processNonQuery(plan);
    } catch (QueryProcessException e) {
      logger.debug("meet error while processing non-query. ", e);
      return RpcUtils.getStatus(e.getErrorCode(), e.getMessage());
    } catch (Exception e) {
      logger.error("{}: server Internal Error: ", IoTDBConstant.GLOBAL_DB_NAME, e);
      return RpcUtils.getStatus(TSStatusCode.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    return execRet
        ? RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS, "Execute successfully")
        : RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR);
  }


  /**
   * A non-partitioned plan (like storage group creation) should be executed on all metagroup nodes,
   * so the MetaLeader should take the responsible to make sure that every node receives the plan.
   * Thus the plan will be processed locally only by the MetaLeader and forwarded by non-leader
   * nodes.
   */
  public TSStatus processNonPartitionedMetaPlan(PhysicalPlan plan) {
    if (character == NodeCharacter.LEADER) {
      TSStatus status = processPlanLocally(plan);
      if (status != null) {
        return status;
      }
    } else if (leader != null) {
      TSStatus result = forwardPlan(plan, leader, null);
      if (!StatusUtils.NO_LEADER.equals(result)) {
        result.setRedirectNode(new EndPoint(leader.getIp(), leader.getClientPort()));
        return result;
      }
    }

    waitLeader();
    // the leader can be itself after waiting
    if (character == NodeCharacter.LEADER) {
      TSStatus status = processPlanLocally(plan);
      if (status != null) {
        return status;
      }
    }
    TSStatus result = forwardPlan(plan, leader, null);
    if (!StatusUtils.NO_LEADER.equals(result)) {
      result.setRedirectNode(new EndPoint(leader.getIp(), leader.getClientPort()));
    }
    return result;
  }

  /**
   * A non-partitioned plan (like DeleteData) should be executed on all data group nodes, so the
   * DataGroupLeader should take the responsible to make sure that every node receives the plan.
   * Thus the plan will be processed locally only by the DataGroupLeader and forwarded by non-leader
   * nodes.
   */
  private TSStatus processNonPartitionedDataPlan(PhysicalPlan plan) {
    if (plan instanceof DeleteTimeSeriesPlan || plan instanceof DeletePlan) {
      try {
        // as delete related plans may have abstract paths (paths with wildcards), we convert
        // them to full paths so the executor nodes will not need to query the metadata holders,
        // eliminating the risk that when they are querying the metadata holders, the timeseries
        // has already been deleted
        ((CMManager) IoTDB.metaManager).convertToFullPaths(plan);
      } catch (PathNotExistException e) {
        return StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR, e.getMessage());
      }
    }
    try {
      syncLeaderWithConsistencyCheck();
      List<PartitionGroup> globalGroups = partitionTable.getGlobalGroups();
      logger.debug("Forwarding global data plan {} to {} groups", plan, globalGroups.size());
      return forwardPlan(globalGroups, plan);
    } catch (CheckConsistencyException e) {
      logger.debug("Forwarding global data plan {} to meta leader {}", plan, leader);
      waitLeader();
      return forwardPlan(plan, leader, null);
    }
  }

  /**
   * A partitioned plan (like batch insertion) will be split into several sub-plans, each belongs to
   * a data group. And these sub-plans will be sent to and executed on the corresponding groups
   * separately.
   */
  public TSStatus processPartitionedPlan(PhysicalPlan plan) throws UnsupportedPlanException {
    logger.debug("{}: Received a partitioned plan {}", name, plan);
    if (partitionTable == null) {
      logger.debug("{}: Partition table is not ready", name);
      return StatusUtils.PARTITION_TABLE_NOT_READY;
    }

    // split the plan into sub-plans that each only involve one data group
    Map<PhysicalPlan, PartitionGroup> planGroupMap;
    try {
      planGroupMap = splitPlan(plan);
    } catch (CheckConsistencyException checkConsistencyException) {
      return StatusUtils
          .getStatus(StatusUtils.CONSISTENCY_FAILURE, checkConsistencyException.getMessage());
    }

    // the storage group is not found locally
    if (planGroupMap == null || planGroupMap.isEmpty()) {
      if ((plan instanceof InsertPlan || plan instanceof CreateTimeSeriesPlan)
          && ClusterDescriptor.getInstance().getConfig().isEnableAutoCreateSchema()) {
        try {
          ((CMManager) IoTDB.metaManager).createSchema(plan);
          return executeNonQueryPlan(plan);
        } catch (MetadataException e) {
          logger.error(
              String.format("Failed to set storage group or create timeseries, because %s", e));
        }
      }
      logger.error("{}: Cannot found storage groups for {}", name, plan);
      return StatusUtils.NO_STORAGE_GROUP;
    }
    logger.debug("{}: The data groups of {} are {}", name, plan, planGroupMap);
    return forwardPlan(planGroupMap, plan);
  }

  /**
   * split a plan into several sub-plans, each belongs to only one data group.
   */
  private Map<PhysicalPlan, PartitionGroup> splitPlan(PhysicalPlan plan)
      throws UnsupportedPlanException, CheckConsistencyException {
    Map<PhysicalPlan, PartitionGroup> planGroupMap = null;
    try {
      planGroupMap = router.splitAndRoutePlan(plan);
    } catch (StorageGroupNotSetException e) {
      // synchronize with the leader to see if this node has unpulled storage groups
      syncLeaderWithConsistencyCheck();
      try {
        planGroupMap = router.splitAndRoutePlan(plan);
      } catch (MetadataException ex) {
        // ignore
      }
    } catch (MetadataException e) {
      logger.error("Cannot route plan {}", plan, e);
    }
    return planGroupMap;
  }

  /**
   * Forward plans to the DataGroupMember of one node in the corresponding group. Only when all
   * nodes time out, will a TIME_OUT be returned.
   *
   * @param planGroupMap sub-plan -> belong data group pairs
   */
  private TSStatus forwardPlan(Map<PhysicalPlan, PartitionGroup> planGroupMap, PhysicalPlan plan) {
    // the error codes from the groups that cannot execute the plan
    TSStatus status;
    if (planGroupMap.size() == 1) {
      status = forwardToSingleGroup(planGroupMap.entrySet().iterator().next());
    } else {
      if (plan instanceof InsertTabletPlan) {
        // InsertTabletPlans contain many rows, each will correspond to a TSStatus as its
        // execution result, as the plan is split and the sub-plans may have interleaving ranges,
        // we must assure that each TSStatus is placed to the right position
        // e.g., an InsertTabletPlan contains 3 rows, row1 and row3 belong to NodeA and row2
        // belongs to NodeB, when NodeA returns a success while NodeB returns a failure, the
        // failure and success should be placed into proper positions in TSStatus.subStatus
        status = forwardInsertTabletPlan(planGroupMap, (InsertTabletPlan) plan);
      } else {
        status = forwardToMultipleGroup(planGroupMap);
      }
    }
    if (plan instanceof InsertPlan
        && status.getCode() == TSStatusCode.TIMESERIES_NOT_EXIST.getStatusCode()
        && ClusterDescriptor.getInstance().getConfig().isEnableAutoCreateSchema()) {
      // try to create timeseries
      if (((InsertPlan) plan).getFailedMeasurements() != null) {
        ((InsertPlan) plan).getPlanFromFailed();
      }
      boolean hasCreate;
      try {
        hasCreate = ((CMManager) IoTDB.metaManager).createTimeseries((InsertPlan) plan);
      } catch (IllegalPathException e) {
        return StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR, e.getMessage());
      }
      if (hasCreate) {
        status = forwardPlan(planGroupMap, plan);
      } else {
        logger.error("{}, Cannot auto create timeseries.", thisNode);
      }
    }
    logger.debug("{}: executed {} with answer {}", name, plan, status);
    return status;
  }

  /**
   * Forward each sub-plan to its belonging data group, and combine responses from the groups.
   *
   * @param planGroupMap sub-plan -> data group pairs
   */
  private TSStatus forwardInsertTabletPlan(Map<PhysicalPlan, PartitionGroup> planGroupMap,
      InsertTabletPlan plan) {
    List<String> errorCodePartitionGroups = new ArrayList<>();
    TSStatus tmpStatus;
    TSStatus[] subStatus = null;
    boolean noFailure = true;
    boolean isBatchFailure = false;
    EndPoint endPoint = null;
    InsertTabletPlan subPlan;
    // send sub-plans to each belonging data group and collect results
    for (Map.Entry<PhysicalPlan, PartitionGroup> entry : planGroupMap.entrySet()) {
      tmpStatus = forwardToSingleGroup(entry);
      subPlan = (InsertTabletPlan) entry.getKey();
      logger.debug("{}: from {},{},{}", name, entry.getKey(), entry.getValue(), tmpStatus);
      noFailure =
          (tmpStatus.getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) && noFailure;
      isBatchFailure = (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode())
          || isBatchFailure;
      if (tmpStatus.isSetRedirectNode() && subPlan.getMaxTime() == plan.getMaxTime()) {
        endPoint = tmpStatus.getRedirectNode();
      }
      if (tmpStatus.getCode() == TSStatusCode.MULTIPLE_ERROR.getStatusCode()) {
        if (subStatus == null) {
          subStatus = new TSStatus[plan.getRowCount()];
          Arrays.fill(subStatus, RpcUtils.SUCCESS_STATUS);
        }
        // set the status from one group to the proper positions of the overall status
        PartitionUtils.reordering((InsertTabletPlan) entry.getKey(), subStatus,
            tmpStatus.subStatus.toArray(new TSStatus[]{}));
      }
      if (tmpStatus.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(String.format("[%s@%s:%s:%s]",
            tmpStatus.getCode(), entry.getValue().getHeader(),
            tmpStatus.getMessage(), tmpStatus.subStatus));
      }
    }

    return concludeFinalStatus(noFailure, endPoint, isBatchFailure, subStatus, errorCodePartitionGroups);
  }

  private TSStatus concludeFinalStatus(boolean noFailure, EndPoint endPoint,
      boolean isBatchFailure, TSStatus[] subStatus, List<String> errorCodePartitionGroups) {
    TSStatus status;
    if (noFailure) {
      status = StatusUtils.OK;
      if (endPoint != null) {
        status = StatusUtils.getStatus(status, endPoint);
      }
    } else if (isBatchFailure) {
      status = RpcUtils.getStatus(Arrays.asList(subStatus));
    } else {
      status = StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR,
          MSG_MULTIPLE_ERROR + errorCodePartitionGroups.toString());
    }
    return status;
  }

  private TSStatus forwardToSingleGroup(Map.Entry<PhysicalPlan, PartitionGroup> entry) {
    TSStatus result;
    if (entry.getValue().contains(thisNode)) {
      // the query should be handled by a group the local node is in, handle it with in the group
      long start;
      if (Timer.ENABLE_INSTRUMENTING) {
        start = System.nanoTime();
      }
      logger.debug("Execute {} in a local group of {}", entry.getKey(),
          entry.getValue().getHeader());
      result = getLocalDataMember(entry.getValue().getHeader())
          .executeNonQueryPlan(entry.getKey());
      Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_LOCAL_GROUP.addNanoFromStart(start);
    } else {
      // forward the query to the group that should handle it
      long start;
      if (Timer.ENABLE_INSTRUMENTING) {
        start = System.nanoTime();
      }
      logger.debug("Forward {} to a remote group of {}", entry.getKey(),
          entry.getValue().getHeader());
      result = forwardPlan(entry.getKey(), entry.getValue());
      Timer.Statistic.META_GROUP_MEMBER_EXECUTE_NON_QUERY_IN_REMOTE_GROUP.addNanoFromStart(start);
    }
    return result;
  }

  /**
   * forward each sub-plan to its corresponding data group, if some groups goes wrong, the error
   * messages from each group will be compacted into one string.
   *
   * @param planGroupMap sub-plan -> data group pairs
   */
  private TSStatus forwardToMultipleGroup(Map<PhysicalPlan, PartitionGroup> planGroupMap) {
    List<String> errorCodePartitionGroups = new ArrayList<>();
    TSStatus tmpStatus;
    boolean allRedirect = true;
    EndPoint endPoint = null;
    for (Map.Entry<PhysicalPlan, PartitionGroup> entry : planGroupMap.entrySet()) {
      tmpStatus = forwardToSingleGroup(entry);
      if (tmpStatus.isSetRedirectNode()) {
        endPoint = tmpStatus.getRedirectNode();
      } else {
        allRedirect = false;
      }
      if (tmpStatus.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(String.format("[%s@%s:%s]",
            tmpStatus.getCode(), entry.getValue().getHeader(),
            tmpStatus.getMessage()));
      }
    }
    TSStatus status;
    if (errorCodePartitionGroups.isEmpty()) {
      status = StatusUtils.OK;
      if (allRedirect) {
        status = StatusUtils.getStatus(status, endPoint);
      }
    } else {
      status = StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR,
          MSG_MULTIPLE_ERROR + errorCodePartitionGroups.toString());
    }
    return status;
  }

  /**
   * Forward a plan to all DataGroupMember groups. Only when all nodes time out, will a TIME_OUT be
   * returned. The error messages from each group (if any) will be compacted into one string.
   *
   * @para plan
   */
  private TSStatus forwardPlan(List<PartitionGroup> partitionGroups, PhysicalPlan plan) {
    // the error codes from the groups that cannot execute the plan
    TSStatus status;
    List<String> errorCodePartitionGroups = new ArrayList<>();
    for (PartitionGroup partitionGroup : partitionGroups) {
      if (partitionGroup.contains(thisNode)) {
        // the query should be handled by a group the local node is in, handle it with in the group
        logger.debug("Execute {} in a local group of {}", plan, partitionGroup.getHeader());
        status = getLocalDataMember(partitionGroup.getHeader())
            .executeNonQueryPlan(plan);
      } else {
        // forward the query to the group that should handle it
        logger.debug("Forward {} to a remote group of {}", plan,
            partitionGroup.getHeader());
        status = forwardPlan(plan, partitionGroup);
      }
      if (status.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        // execution failed, record the error message
        errorCodePartitionGroups.add(String.format("[%s@%s:%s]",
            status.getCode(), partitionGroup.getHeader(),
            status.getMessage()));
      }
    }
    if (errorCodePartitionGroups.isEmpty()) {
      status = StatusUtils.OK;
    } else {
      status = StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR,
          MSG_MULTIPLE_ERROR + errorCodePartitionGroups.toString());
    }
    logger.debug("{}: executed {} with answer {}", name, plan, status);
    return status;
  }

  /**
   * Forward a plan to the DataGroupMember of one node in the group. Only when all nodes time out,
   * will a TIME_OUT be returned.
   */
  private TSStatus forwardPlan(PhysicalPlan plan, PartitionGroup group) {
    for (Node node : group) {
      TSStatus status;
      try {
        // only data plans are partitioned, so it must be processed by its data server instead of
        // meta server
        if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
          status = forwardDataPlanAsync(plan, node, group.getHeader());
        } else {
          status = forwardDataPlanSync(plan, node, group.getHeader());
        }
      } catch (IOException e) {
        status = StatusUtils.getStatus(StatusUtils.EXECUTE_STATEMENT_ERROR, e.getMessage());
      }
      if (!StatusUtils.TIME_OUT.equals(status)) {
        if (!status.isSetRedirectNode()) {
          status.setRedirectNode(new EndPoint(node.getIp(), node.getClientPort()));
        }
        return status;
      } else {
        logger.warn("Forward {} to {} timed out", plan, node);
      }
    }
    logger.warn("Forward {} to {} timed out", plan, group);
    return StatusUtils.TIME_OUT;
  }

  /**
   * Forward a non-query plan to the data port of "receiver"
   *
   * @param plan a non-query plan
   * @param header to determine which DataGroupMember of "receiver" will process the request.
   * @return a TSStatus indicating if the forwarding is successful.
   */
  private TSStatus forwardDataPlanAsync(PhysicalPlan plan, Node receiver, Node header)
      throws IOException {
    RaftService.AsyncClient client = getClientProvider().getAsyncDataClient(receiver,
        RaftServer.getWriteOperationTimeoutMS());
    return forwardPlanAsync(plan, receiver, header, client);
  }

  private TSStatus forwardDataPlanSync(PhysicalPlan plan, Node receiver, Node header) {
    Client client = getClientProvider().getSyncDataClient(receiver,
        RaftServer.getWriteOperationTimeoutMS());
    return forwardPlanSync(plan, receiver, header, client);
  }

  /**
   * Get the data groups that should be queried when querying "path" with "filter". First, the time
   * interval qualified by the filter will be extracted. If any side of the interval is open, query
   * all groups. Otherwise compute all involved groups w.r.t. the time partitioning.
   */
  public List<PartitionGroup> routeFilter(Filter filter, PartialPath path) throws
      StorageEngineException {
    Intervals intervals = PartitionUtils.extractTimeInterval(filter);
    return routeIntervals(intervals, path);
  }

  public List<PartitionGroup> routeIntervals(Intervals intervals, PartialPath path)
      throws StorageEngineException {
    List<PartitionGroup> partitionGroups = new ArrayList<>();
    long firstLB = intervals.getLowerBound(0);
    long lastUB = intervals.getUpperBound(intervals.getIntervalSize() - 1);

    if (firstLB == Long.MIN_VALUE || lastUB == Long.MAX_VALUE) {
      // as there is no TimeLowerBound or TimeUpperBound, the query should be broadcast to every
      // group
      partitionGroups.addAll(partitionTable.getGlobalGroups());
    } else {
      // compute the related data groups of all intervals
      // TODO-Cluster#690: change to a broadcast when the computation is too expensive
      try {
        PartialPath storageGroupName = IoTDB.metaManager
            .getStorageGroupPath(path);
        Set<Node> groupHeaders = new HashSet<>();
        for (int i = 0; i < intervals.getIntervalSize(); i++) {
          // compute the headers of groups involved in every interval
          PartitionUtils
              .getIntervalHeaders(storageGroupName.getFullPath(), intervals.getLowerBound(i),
                  intervals.getUpperBound(i), partitionTable, groupHeaders);
        }
        // translate the headers to groups
        for (Node groupHeader : groupHeaders) {
          partitionGroups.add(partitionTable.getHeaderGroup(groupHeader));
        }
      } catch (MetadataException e) {
        throw new StorageEngineException(e);
      }
    }
    return partitionGroups;
  }

  @SuppressWarnings("java:S2274")
  public Map<Node, Boolean> getAllNodeStatus() {
    if (getPartitionTable() == null) {
      // the cluster is being built.
      return null;
    }
    Map<Node, Boolean> nodeStatus = new HashMap<>();
    for (Node node : allNodes) {
      nodeStatus.put(node, thisNode.equals(node));
    }

    try {
      if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
        getNodeStatusAsync(nodeStatus);
      } else {
        getNodeStatusSync(nodeStatus);
      }
    } catch (TException e) {
      logger.warn("Cannot get the status of all nodes", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn("Cannot get the status of all nodes", e);
    }
    return nodeStatus;
  }

  @SuppressWarnings({"java:S2445", "java:S2274"})
  private void getNodeStatusAsync(Map<Node, Boolean> nodeStatus)
      throws TException, InterruptedException {
    NodeStatusHandler nodeStatusHandler = new NodeStatusHandler(nodeStatus);
    synchronized (nodeStatus) {
      for (Node node : allNodes) {
        TSMetaService.AsyncClient client = (AsyncClient) getAsyncClient(node);
        if (!node.equals(thisNode) && client != null) {
          client.checkAlive(nodeStatusHandler);
        }
      }
      nodeStatus.wait(ClusterConstant.CHECK_ALIVE_TIME_OUT_MS);
    }
  }

  private void getNodeStatusSync(Map<Node, Boolean> nodeStatus) throws TException {
    NodeStatusHandler nodeStatusHandler = new NodeStatusHandler(nodeStatus);
    for (Node node : allNodes) {
      SyncMetaClient client = (SyncMetaClient) getSyncClient(node);
      if (!node.equals(thisNode) && client != null) {
        nodeStatusHandler.onComplete(client.checkAlive());
      }
    }
  }

  @TestOnly
  public void setPartitionTable(PartitionTable partitionTable) {
    this.partitionTable = partitionTable;
    router = new ClusterPlanRouter(partitionTable);
    DataClusterServer dClusterServer = getDataClusterServer();
    if (dClusterServer != null) {
      dClusterServer.setPartitionTable(partitionTable);
    }
  }


  /**
   * Process the request of removing a node from the cluster. Reject the request if partition table
   * is unavailable or the node is not the MetaLeader and it does not know who the leader is.
   * Otherwise (being the MetaLeader), the request will be processed locally and broadcast to every
   * node.
   *
   * @param node the node to be removed.
   */
  public long removeNode(Node node)
      throws PartitionTableUnavailableException, LogExecutionException {
    if (partitionTable == null) {
      logger.info("Cannot add node now because the partition table is not set");
      throw new PartitionTableUnavailableException(thisNode);
    }

    waitLeader();
    // try to process the request locally, if it cannot be processed locally, forward it
    return processRemoveNodeLocally(node);
  }


  /**
   * Process a node removal request locally and broadcast it to the whole cluster. The removal will
   * be rejected if number of nodes will fall below half of the replication number after this
   * operation.
   *
   * @param node the node to be removed.
   * @return Long.MIN_VALUE if further forwarding is required, or the execution result
   */
  private long processRemoveNodeLocally(Node node)
      throws LogExecutionException {
    if (character != NodeCharacter.LEADER) {
      return Response.RESPONSE_NULL;
    }

    // if we cannot have enough replica after the removal, reject it
    if (allNodes.size() <= ClusterDescriptor.getInstance().getConfig().getReplicationNum()) {
      return Response.RESPONSE_CLUSTER_TOO_SMALL;
    }

    // find the node to be removed in the node list
    Node target = null;
    synchronized (allNodes) {
      for (Node n : allNodes) {
        if (n.ip.equals(node.ip) && n.metaPort == node.metaPort) {
          target = n;
          break;
        }
      }
    }

    if (target == null) {
      logger.debug("Node {} is not in the cluster", node);
      return Response.RESPONSE_REJECT;
    }

    // node removal must be serialized to reduce potential concurrency problem
    synchronized (logManager) {
      RemoveNodeLog removeNodeLog = new RemoveNodeLog();
      removeNodeLog.setCurrLogTerm(getTerm().get());
      removeNodeLog.setCurrLogIndex(logManager.getLastLogIndex() + 1);

      removeNodeLog.setRemovedNode(target);

      logManager.append(removeNodeLog);

      int retryTime = 1;
      while (true) {
        logger.info("Send the node removal request of {} to other nodes, retry time: {}", target,
            retryTime);
        AppendLogResult result = sendLogToAllGroups(removeNodeLog);

        switch (result) {
          case OK:
            logger.info("Removal request of {} is accepted", target);
            commitLog(removeNodeLog);
            return Response.RESPONSE_AGREE;
          case TIME_OUT:
            logger.info("Removal request of {} timed out", target);
            break;
          // retry
          case LEADERSHIP_STALE:
          default:
            return Response.RESPONSE_NULL;
        }
      }
    }
  }

  /**
   * Remove a node from the node list, partition table and update DataGroupMembers. If the removed
   * node is the local node, also stop heartbeat and catch-up service of metadata, but the heartbeat
   * and catch-up service of data are kept alive for other nodes to pull data. If the removed node
   * is a leader, send an exile to the removed node so that it can know it is removed.
   *
   * @param oldNode the node to be removed
   */
  public void applyRemoveNode(Node oldNode) {
    synchronized (allNodes) {
      if (allNodes.contains(oldNode)) {
        logger.debug("Removing a node {} from {}", oldNode, allNodes);
        allNodes.remove(oldNode);
        idNodeMap.remove(oldNode.nodeIdentifier);

        // update the partition table
        NodeRemovalResult result = partitionTable.removeNode(oldNode);

        // update DataGroupMembers, as the node is removed, the members of some groups are
        // changed and there will also be one less group
        getDataClusterServer().removeNode(oldNode, result);
        // the leader is removed, start the next election ASAP
        if (oldNode.equals(leader)) {
          setCharacter(NodeCharacter.ELECTOR);
          lastHeartbeatReceivedTime = Long.MIN_VALUE;
        }

        if (oldNode.equals(thisNode)) {
          // use super.stop() so that the data server will not be closed because other nodes may
          // want to pull data from this node
          super.stop();
          if (clientServer != null) {
            clientServer.stop();
          }
        } else if (thisNode.equals(leader)) {
          // as the old node is removed, it cannot know this by heartbeat or log, so it should be
          // directly kicked out of the cluster
          exileNode(oldNode);
        }

        // save the updated partition table
        savePartitionTable();
      }
    }
  }

  private void exileNode(Node node) {
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncMetaClient asyncMetaClient = (AsyncMetaClient) getAsyncClient(node);
      try {
        asyncMetaClient.exile(new GenericHandler<>(node, null));
      } catch (TException e) {
        logger.warn("Cannot inform {} its removal", node, e);
      }
    } else {
      SyncMetaClient client = (SyncMetaClient) getSyncClient(node);
      try {
        client.exile();
      } catch (TException e) {
        logger.warn("Cannot inform {} its removal", node, e);
      }
      ClientUtils.putBackSyncClient(client);
    }
  }

  /**
   * Generate a report containing the character, leader, term, last log and read-only-status. This
   * will help to see if the node is in a consistent and right state during debugging.
   */
  private MetaMemberReport genMemberReport() {
    long prevLastLogIndex = lastReportedLogIndex;
    lastReportedLogIndex = logManager.getLastLogIndex();
    return new MetaMemberReport(character, leader, term.get(),
        logManager.getLastLogTerm(), lastReportedLogIndex, logManager.getCommitLogIndex()
        , logManager.getCommitLogTerm(), readOnly, lastHeartbeatReceivedTime, prevLastLogIndex,
        logManager.getMaxHaveAppliedCommitIndex());
  }

  /**
   * Generate a report containing the status of both MetaGroupMember and DataGroupMembers of this
   * node. This will help to see if the node is in a consistent and right state during debugging.
   */
  private NodeReport genNodeReport() {
    NodeReport report = new NodeReport(thisNode);
    report.setMetaMemberReport(genMemberReport());
    report.setDataMemberReportList(dataClusterServer.genMemberReports());
    return report;
  }

  @Override
  public void setAllNodes(List<Node> allNodes) {
    super.setAllNodes(allNodes);
    initPeerMap();
    idNodeMap = new HashMap<>();
    for (Node node : allNodes) {
      idNodeMap.put(node.getNodeIdentifier(), node);
    }
  }

  /**
   * Get a local DataGroupMember that is in the group of "header" and should process "request".
   *
   * @param header the header of the group which the local node is in
   * @param request the toString() of this parameter should explain what the request is and it is
   * only used in logs for tracing
   */
  public DataGroupMember getLocalDataMember(Node header, Object request) {
    return dataClusterServer.getDataMember(header, null, request);
  }

  /**
   * Get a local DataGroupMember that is in the group of "header" for an internal request.
   *
   * @param header the header of the group which the local node is in
   */
  public DataGroupMember getLocalDataMember(Node header) {
    return dataClusterServer.getDataMember(header, null, "Internal call");
  }

  public DataClientProvider getClientProvider() {
    return dataClientProvider;
  }

  @Override
  public void closeLogManager() {
    super.closeLogManager();
    if (dataClusterServer != null) {
      dataClusterServer.closeLogManagers();
    }
  }

  private PlanExecutor getLocalExecutor() throws QueryProcessException {
    if (localExecutor == null) {
      localExecutor = new PlanExecutor();
    }
    return localExecutor;
  }

  public StartUpStatus getStartUpStatus() {
    return startUpStatus;
  }

  @TestOnly
  public void setClientProvider(DataClientProvider dataClientProvider) {
    this.dataClientProvider = dataClientProvider;
  }
}