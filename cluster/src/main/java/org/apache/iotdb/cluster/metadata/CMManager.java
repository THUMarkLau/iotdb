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

package org.apache.iotdb.cluster.metadata;

import static org.apache.iotdb.db.utils.EncodingInferenceUtils.getDefaultEncoding;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.client.sync.SyncClientAdaptor;
import org.apache.iotdb.cluster.client.sync.SyncDataClient;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.partition.PartitionGroup;
import org.apache.iotdb.cluster.query.manage.QueryCoordinator;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.PullSchemaRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSchemaResp;
import org.apache.iotdb.cluster.server.RaftServer;
import org.apache.iotdb.cluster.server.member.MetaGroupMember;
import org.apache.iotdb.cluster.utils.ClientUtils;
import org.apache.iotdb.cluster.utils.ClusterUtils;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.metadata.MeasurementMeta;
import org.apache.iotdb.db.metadata.MetaUtils;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.metadata.mnode.MeasurementMNode;
import org.apache.iotdb.db.qp.constant.SQLConstant;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.SetStorageGroupPlan;
import org.apache.iotdb.db.service.IoTDB;
import org.apache.iotdb.db.utils.SchemaUtils;
import org.apache.iotdb.db.utils.TypeInferenceUtils;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TSStatus;
import org.apache.iotdb.tsfile.common.cache.LRUCache;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.schema.TimeseriesSchema;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("java:S1135") // ignore todos
public class CMManager extends MManager {

  private static final Logger logger = LoggerFactory.getLogger(CMManager.class);

  private ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
  // only cache the series who is writing, we need not to cache series who is reading
  // because the read is slow, so pull from remote is little cost comparing to the disk io
  private RemoteMetaCache mRemoteMetaCache;
  private MetaPuller metaPuller;
  private MetaGroupMember metaGroupMember;

  private CMManager() {
    super();
    metaPuller = MetaPuller.getInstance();
    int remoteCacheSize = config.getmRemoteSchemaCacheSize();
    mRemoteMetaCache = new RemoteMetaCache(remoteCacheSize);
  }

  private static class MManagerHolder {

    private MManagerHolder() {
      // allowed to do nothing
    }

    private static final CMManager INSTANCE = new CMManager();
  }

  /**
   * we should not use this function in other place, but only in IoTDB class
   * @return
   */
  public static CMManager getInstance() {
    return CMManager.MManagerHolder.INSTANCE;
  }

  @Override
  public String deleteTimeseries(PartialPath prefixPath) throws MetadataException {
    cacheLock.writeLock().lock();
    mRemoteMetaCache.removeItem(prefixPath);
    cacheLock.writeLock().unlock();
    return super.deleteTimeseries(prefixPath);
  }

  @Override
  public void deleteStorageGroups(List<PartialPath> storageGroups) throws MetadataException {
    cacheLock.writeLock().lock();
    for (PartialPath storageGroup : storageGroups) {
      mRemoteMetaCache.removeItem(storageGroup);
    }
    cacheLock.writeLock().unlock();
    super.deleteStorageGroups(storageGroups);
  }

  @Override
  public TSDataType getSeriesType(PartialPath path) throws MetadataException {
    // try remote cache first
    try {
      cacheLock.readLock().lock();
      MeasurementMeta measurementMeta = mRemoteMetaCache.get(path);
      if (measurementMeta != null) {
        return measurementMeta.getMeasurementSchema().getType();
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    // try local MTree
    TSDataType seriesType;
    try {
      seriesType = super.getSeriesType(path);
    } catch (PathNotExistException e) {
      // pull from remote node
      List<MeasurementSchema> schemas = metaPuller
          .pullMeasurementSchemas(Collections.singletonList(path));
      if (!schemas.isEmpty()) {
        cacheMeta(path, new MeasurementMeta(schemas.get(0)));
        return schemas.get(0).getType();
      } else {
        throw e;
      }
    }
    return seriesType;
  }

  /**
   *  the org.apache.iotdb.db.writelog.recover.logReplayer will call this to get schema after restart
   *  we should retry to get schema util we get the schema
   * @param deviceId
   * @param measurements
   * @return
   * @throws MetadataException
   */
  @Override
  public MeasurementSchema[] getSchemas(PartialPath deviceId, String[] measurements) throws MetadataException {
    try {
      return super.getSchemas(deviceId, measurements);
    } catch (MetadataException e) {
      // some measurements not exist in local
      // try cache
      MeasurementSchema[] measurementSchemas = new MeasurementSchema[measurements.length];
      int failedMeasurementIndex = getSchemasLocally(deviceId, measurements, measurementSchemas);
      if (failedMeasurementIndex == -1) {
        return measurementSchemas;
      }

      // will retry util get schema
      pullSeriesSchemas(deviceId, measurements);

      // try again
      failedMeasurementIndex = getSchemasLocally(deviceId, measurements, measurementSchemas);
      if (failedMeasurementIndex != -1) {
        throw new MetadataException(deviceId.getFullPath() + IoTDBConstant.PATH_SEPARATOR
          + measurements[failedMeasurementIndex] + " is not found");
      }
      return measurementSchemas;
    }
  }

  /**
   *
   * @return -1 if all schemas are found, or the first index of the non-exist schema
   */
  private int getSchemasLocally(PartialPath deviceId, String[] measurements,
      MeasurementSchema[] measurementSchemas) {
    int failedMeasurementIndex = -1;
    cacheLock.readLock().lock();
    try {
      for (int i = 0; i < measurements.length && failedMeasurementIndex == -1; i++) {
        MeasurementMeta measurementMeta =
            mRemoteMetaCache.get(deviceId.concatNode(measurements[i]));
        if (measurementMeta == null) {
          failedMeasurementIndex = i;
        } else {
          measurementSchemas[i] = measurementMeta.getMeasurementSchema();
        }
      }
    } finally {
      cacheLock.readLock().unlock();
    }
    return failedMeasurementIndex;
  }

  private void pullSeriesSchemas(PartialPath deviceId, String[] measurementList)
    throws MetadataException {
    List<PartialPath> schemasToPull = new ArrayList<>();
    for (String s : measurementList) {
      schemasToPull.add(deviceId.concatNode(s));
    }
    List<MeasurementSchema> schemas = metaPuller.pullMeasurementSchemas(schemasToPull);
    for (MeasurementSchema schema : schemas) {
      cacheMeta(deviceId.concatNode(schema.getMeasurementId()),
          new MeasurementMeta(schema));
    }
    logger.debug("Pulled {}/{} schemas from remote", schemas.size(), measurementList.length);
  }

  @Override
  public void cacheMeta(PartialPath seriesPath, MeasurementMeta meta) {
    cacheLock.writeLock().lock();
    mRemoteMetaCache.put(seriesPath, meta);
    cacheLock.writeLock().unlock();
  }

  @Override
  public void updateLastCache(PartialPath seriesPath, TimeValuePair timeValuePair,
      boolean highPriorityUpdate, Long latestFlushedTime,
      MeasurementMNode node) {
    cacheLock.writeLock().lock();
    try {
      MeasurementMeta measurementMeta = mRemoteMetaCache.get(seriesPath);
      if (measurementMeta != null) {
        measurementMeta.updateCachedLast(timeValuePair, highPriorityUpdate, latestFlushedTime);
      }
    } finally {
      cacheLock.writeLock().unlock();
    }
    // maybe local also has the timeseries
    super.updateLastCache(seriesPath, timeValuePair, highPriorityUpdate, latestFlushedTime,
        node);
  }

  @Override
  public TimeValuePair getLastCache(PartialPath seriesPath) {
    MeasurementMeta measurementMeta = mRemoteMetaCache.get(seriesPath);
    if (measurementMeta != null) {
      return measurementMeta.getTimeValuePair();
    }

    return super.getLastCache(seriesPath);
  }

  @Override
  public MeasurementSchema[] getSeriesSchemasAndReadLockDevice(PartialPath deviceId,
      String[] measurementList, InsertPlan plan) throws MetadataException {
    MeasurementSchema[] measurementSchemas = new MeasurementSchema[measurementList.length];
    getDeviceNode(deviceId).readLock();
    int nonExistSchemaIndex = getSchemasLocally(deviceId, measurementList, measurementSchemas);
    if (nonExistSchemaIndex == -1) {
      return measurementSchemas;
    } else {
      unlockDeviceReadLock(deviceId);
    }
    // auto-create schema in IoTDBConfig is always disabled in the cluster version, and we have
    // another config in ClusterConfig to do this
    return super.getSeriesSchemasAndReadLockDevice(deviceId, measurementList, plan);
  }

  @Override
  public MeasurementSchema getSeriesSchema(PartialPath device, String measurement) throws MetadataException {
    try {
      MeasurementSchema measurementSchema = super.getSeriesSchema(device, measurement);
      if (measurementSchema != null) {
        return measurementSchema;
      }
    } catch (PathNotExistException e) {
      // not found in local
    }

    // try cache
    cacheLock.readLock().lock();
    try {
      MeasurementMeta measurementMeta = mRemoteMetaCache.get(device.concatNode(measurement));
      if (measurementMeta != null) {
        return measurementMeta.getMeasurementSchema();
      }
    } finally {
      cacheLock.readLock().unlock();
    }

    // pull from remote
    pullSeriesSchemas(device, new String[]{measurement});

    // try again
    cacheLock.readLock().lock();
    try {
      MeasurementMeta measurementMeta =
          mRemoteMetaCache.get(device.concatNode(measurement));
      if (measurementMeta != null) {
        return measurementMeta.getMeasurementSchema();
      }
    } finally {
      cacheLock.readLock().unlock();
    }
    return super.getSeriesSchema(device, measurement);
  }

  private static class RemoteMetaCache extends LRUCache<PartialPath, MeasurementMeta> {

    RemoteMetaCache(int cacheSize) {
      super(cacheSize);
    }

    @Override
    protected MeasurementMeta loadObjectByKey(PartialPath key) {
      return null;
    }

    @Override
    public synchronized void removeItem(PartialPath key) {
      cache.keySet().removeIf(s -> s.getFullPath().startsWith(key.getFullPath()));
    }

    @Override
    public synchronized MeasurementMeta get(PartialPath key) {
      try {
        return super.get(key);
      } catch (IOException e) {
        // not happening
        return null;
      }
    }
  }

  /**
   * create storage groups for CreateTimeseriesPlan and InsertPlan, also create timeseries for
   * InsertPlan. Only the two kind of plans can use this method.
   */
  public void createSchema(PhysicalPlan plan) throws MetadataException {
    // try to set storage group
    PartialPath deviceId;
    if (plan instanceof InsertPlan) {
      deviceId = ((InsertPlan) plan).getDeviceId();
    } else {
      deviceId = ((CreateTimeSeriesPlan) plan).getPath();
    }

    PartialPath storageGroupName = MetaUtils
        .getStorageGroupPathByLevel(deviceId, IoTDBDescriptor.getInstance()
            .getConfig().getDefaultStorageGroupLevel());
    SetStorageGroupPlan setStorageGroupPlan = new SetStorageGroupPlan(
        storageGroupName);
    TSStatus setStorageGroupResult =
        metaGroupMember.processNonPartitionedMetaPlan(setStorageGroupPlan);
    if (setStorageGroupResult.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode() &&
        setStorageGroupResult.getCode() != TSStatusCode.PATH_ALREADY_EXIST_ERROR
            .getStatusCode()) {
      throw new MetadataException(
          String.format("Status Code: %d, failed to set storage group %s",
              setStorageGroupResult.getCode(), storageGroupName)
      );
    }
    if (plan instanceof InsertPlan) {
      // try to create timeseries
      boolean isAutoCreateTimeseriesSuccess = createTimeseries((InsertPlan) plan);
      if (!isAutoCreateTimeseriesSuccess) {
        throw new MetadataException(
            "Failed to create timeseries from InsertPlan automatically."
        );
      }
    }
  }

  /**
   * Create timeseries automatically for an InsertPlan.
   *
   * @param insertPlan, some of the timeseries in it are not created yet
   * @return true of all uncreated timeseries are created
   */
  public boolean createTimeseries(InsertPlan insertPlan) throws IllegalPathException {
    List<String> seriesList = new ArrayList<>();
    PartialPath deviceId = insertPlan.getDeviceId();
    PartialPath storageGroupName;
    try {
      storageGroupName = MetaUtils
          .getStorageGroupPathByLevel(deviceId, IoTDBDescriptor.getInstance()
              .getConfig().getDefaultStorageGroupLevel());
    } catch (MetadataException e) {
      logger.error("Failed to infer storage group from deviceId {}", deviceId);
      return false;
    }
    for (String measurementId : insertPlan.getMeasurements()) {
      seriesList.add(deviceId.getFullPath() + TsFileConstant.PATH_SEPARATOR + measurementId);
    }
    PartitionGroup partitionGroup =
        metaGroupMember.getPartitionTable().route(storageGroupName.getFullPath(), 0);
    List<String> unregisteredSeriesList = getUnregisteredSeriesList(seriesList, partitionGroup);

    return createTimeseries(unregisteredSeriesList, seriesList, insertPlan);
  }

  /**
   * create timeseries from paths in "unregisteredSeriesList". If data types are provided by the
   * InsertPlan, use them, otherwise infer the types from the values. Use default encodings and
   * compressions of the corresponding data type.
   */
  private boolean createTimeseries(List<String> unregisteredSeriesList,
      List<String> seriesList, InsertPlan insertPlan) throws IllegalPathException {
    for (String seriesPath : unregisteredSeriesList) {
      int index = seriesList.indexOf(seriesPath);
      TSDataType dataType;
      // use data types in insertPlan if provided, otherwise infer them from the values
      if (insertPlan.getDataTypes() != null && insertPlan.getDataTypes()[index] != null) {
        dataType = insertPlan.getDataTypes()[index];
      } else {
        dataType = TypeInferenceUtils.getPredictedDataType(insertPlan instanceof InsertTabletPlan
            ? Array.get(((InsertTabletPlan) insertPlan).getColumns()[index], 0)
            : ((InsertRowPlan) insertPlan).getValues()[index], true);
      }
      // use default encoding and compression from the config
      TSEncoding encoding = getDefaultEncoding(dataType);
      CompressionType compressionType = TSFileDescriptor.getInstance().getConfig().getCompressor();
      CreateTimeSeriesPlan createTimeSeriesPlan = new CreateTimeSeriesPlan(
          new PartialPath(seriesPath),
          dataType, encoding, compressionType, null, null, null, null);
      // TODO-Cluster: add executeNonQueryBatch() to create the series in batch
      TSStatus result;
      try {
        result = metaGroupMember.processPartitionedPlan(createTimeSeriesPlan);
      } catch (UnsupportedPlanException e) {
        logger.error("Failed to create timeseries {} automatically. Unsupported plan exception {} ",
            seriesPath, e.getMessage());
        return false;
      }
      if (result.getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        logger.error("{} failed to execute create timeseries {}", metaGroupMember.getThisNode(),
            seriesPath);
        return false;
      }
    }
    return true;
  }

  public void setMetaGroupMember(MetaGroupMember metaGroupMember) {
    this.metaGroupMember = metaGroupMember;
  }

  /**
   * To check which timeseries in the input list is unregistered from one node in "partitionGroup".
   */
  private List<String> getUnregisteredSeriesList(List<String> seriesList,
      PartitionGroup partitionGroup) {
    for (Node node : partitionGroup) {
      try {
        List<String> result;
        if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
          AsyncDataClient client = metaGroupMember.getClientProvider().getAsyncDataClient(node,
              RaftServer.getReadOperationTimeoutMS());
          result = SyncClientAdaptor
              .getUnregisteredMeasurements(client, partitionGroup.getHeader(), seriesList);
        } else {
          SyncDataClient syncDataClient =
              metaGroupMember.getClientProvider().getSyncDataClient(node,
              RaftServer.getReadOperationTimeoutMS());
          result = syncDataClient.getUnregisteredTimeseries(partitionGroup.getHeader(), seriesList);
          ClientUtils.putBackSyncClient(syncDataClient);
        }

        if (result != null) {
          return result;
        }
      } catch (TException | IOException e) {
        logger.error("{}: cannot getting unregistered {} and other {} paths from {}",
            metaGroupMember.getName(),
            seriesList.get(0), seriesList.get(seriesList.size() - 1), node, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.error("{}: getting unregistered series list {} ... {} is interrupted from {}",
            metaGroupMember.getName(),
            seriesList.get(0), seriesList.get(seriesList.size() - 1), node, e);
      }
    }
    return Collections.emptyList();
  }

  /**
   * Pull the all timeseries schemas of given prefixPaths from remote nodes. All prefixPaths must
   * contain a storage group. The pulled schemas will be cache in CMManager.
   *
   * @param ignoredGroup do not pull schema from the group to avoid backward dependency. If a user
   * send an insert request before registering schemas, then this method may pull schemas from the
   * same groups. If this method is called by an applier, it holds the lock of LogManager, while the
   * pulling thread may want this lock too, resulting in a deadlock.
   */
  public void pullTimeSeriesSchemas(List<PartialPath> prefixPaths,
      Node ignoredGroup)
      throws MetadataException {
    logger.debug("{}: Pulling timeseries schemas of {}", metaGroupMember.getName(), prefixPaths);
    // split the paths by the data groups that should hold them
    Map<PartitionGroup, List<String>> partitionGroupPathMap = new HashMap<>();
    for (PartialPath prefixPath : prefixPaths) {
      if (SQLConstant.RESERVED_TIME.equalsIgnoreCase(prefixPath.getFullPath())) {
        continue;
      }
      PartitionGroup partitionGroup = ClusterUtils
          .partitionByPathTimeWithSync(prefixPath, metaGroupMember);
      if (!partitionGroup.getHeader().equals(ignoredGroup)) {
        partitionGroupPathMap.computeIfAbsent(partitionGroup, g -> new ArrayList<>())
            .add(prefixPath.getFullPath());
      }
    }

    // pull timeseries schema from every group involved
    if (logger.isDebugEnabled()) {
      logger.debug("{}: pulling schemas of {} and other {} paths from {} groups", metaGroupMember.getName(),
          prefixPaths.get(0), prefixPaths.size() - 1,
          partitionGroupPathMap.size());
    }
    for (Entry<PartitionGroup, List<String>> partitionGroupListEntry : partitionGroupPathMap
        .entrySet()) {
      PartitionGroup partitionGroup = partitionGroupListEntry.getKey();
      List<String> paths = partitionGroupListEntry.getValue();
      pullTimeSeriesSchemas(partitionGroup, paths);
    }
  }

  /**
   * Pull timeseries schemas of "prefixPaths" from "partitionGroup". If this node is a member of
   * "partitionGroup", synchronize with the group leader and collect local schemas. Otherwise pull
   * schemas from one node in the group. The pulled schemas will be cached in CMManager.
   */
  private void pullTimeSeriesSchemas(PartitionGroup partitionGroup,
      List<String> prefixPaths) {
    if (partitionGroup.contains(metaGroupMember.getThisNode())) {
      // the node is in the target group, synchronize with leader should be enough
      metaGroupMember.getLocalDataMember(partitionGroup.getHeader(),
          "Pull timeseries of " + prefixPaths).syncLeader();
      return;
    }

    // pull schemas from a remote node
    PullSchemaRequest pullSchemaRequest = new PullSchemaRequest();
    pullSchemaRequest.setHeader(partitionGroup.getHeader());
    pullSchemaRequest.setPrefixPaths(prefixPaths);

    // decide the node access order with the help of QueryCoordinator
    List<Node> nodes = QueryCoordinator.getINSTANCE().reorderNodes(partitionGroup);
    for (Node node : nodes) {
      if (tryPullTimeSeriesSchemas(node, pullSchemaRequest)) {
        break;
      }
    }
  }

  /**
   * send the PullSchemaRequest to "node" and cache the results in CMManager if they are
   * successfully returned.
   *
   * @return true if the pull succeeded, false otherwise
   */
  private boolean tryPullTimeSeriesSchemas(Node node, PullSchemaRequest request) {
    if (logger.isDebugEnabled()) {
      logger.debug("{}: Pulling timeseries schemas of {} and other {} paths from {}",
          metaGroupMember.getName(),
          request.getPrefixPaths().get(0), request.getPrefixPaths().size() - 1, node);
    }

    List<TimeseriesSchema> schemas = null;
    try {
      schemas = pullTimeSeriesSchemas(node, request);
    } catch (IOException | TException e) {
      logger
          .error("{}: Cannot pull timeseries schemas of {} and other {} paths from {}",
              metaGroupMember.getName(),
              request.getPrefixPaths().get(0), request.getPrefixPaths().size() - 1, node, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger
          .error("{}: Cannot pull timeseries schemas of {} and other {} paths from {}",
              metaGroupMember.getName(),
              request.getPrefixPaths().get(0), request.getPrefixPaths().size() - 1, node, e);
    }

    if (schemas != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Pulled {} timeseries schemas of {} and other {} paths from {} of {}",
            metaGroupMember.getName(), schemas.size(), request.getPrefixPaths().get(0),
            request.getPrefixPaths().size() - 1, node, request.getHeader());
      }
      for (TimeseriesSchema schema : schemas) {
        SchemaUtils.cacheTimeseriesSchema(schema);
      }
      return true;
    }
    return false;
  }

  /**
   * send a PullSchemaRequest to a node to pull TimeseriesSchemas, and return the pulled schema or
   * null if there was a timeout.
   */
  private List<TimeseriesSchema> pullTimeSeriesSchemas(Node node,
      PullSchemaRequest request) throws TException, InterruptedException, IOException {
    List<TimeseriesSchema> schemas;
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncDataClient client = metaGroupMember.getClientProvider().getAsyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      schemas = SyncClientAdaptor.pullTimeseriesSchema(client, request);
    } else {
      SyncDataClient syncDataClient = metaGroupMember.getClientProvider().getSyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      PullSchemaResp pullSchemaResp = syncDataClient.pullTimeSeriesSchema(request);
      ByteBuffer buffer = pullSchemaResp.schemaBytes;
      int size = buffer.getInt();
      schemas = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        schemas.add(TimeseriesSchema.deserializeFrom(buffer));
      }
    }

    return schemas;
  }

  /**
   * Get the data types of "paths". If "aggregation" is not null, every path will use the
   * aggregation. First get types locally and if some paths does not exists, pull them from other
   * nodes.
   *
   * @return the left one of the pair is the column types (considering aggregation), the right one
   * of the pair is the measurement types (not considering aggregation)
   */
  public Pair<List<TSDataType>, List<TSDataType>> getSeriesTypesByPaths(List<PartialPath> pathStrs,
      String aggregation) throws MetadataException {
    try {
      return getSeriesTypesByPathsLocally(pathStrs, aggregation);
    } catch (PathNotExistException e) {
      // pull schemas remotely and cache them
      pullTimeSeriesSchemas(pathStrs, null);
      return getSeriesTypesByPathsLocally(pathStrs, aggregation);
    }
  }

  private Pair<List<TSDataType>, List<TSDataType>> getSeriesTypesByPathsLocally(List<PartialPath> pathStrs,
      String aggregation) throws MetadataException {
    List<TSDataType> measurementDataTypes = SchemaUtils.getSeriesTypesByPaths(pathStrs,
        (String) null);
    // if the aggregation function is null, the type of column in result set
    // is equal to the real type of the measurement
    if (aggregation == null) {
      return new Pair<>(measurementDataTypes, measurementDataTypes);
    } else {
      // if the aggregation function is not null,
      // we should recalculate the type of column in result set
      List<TSDataType> columnDataTypes = SchemaUtils
          .getAggregatedDataTypes(measurementDataTypes, aggregation);
      return new Pair<>(columnDataTypes, measurementDataTypes);
    }
  }

  /**
   * Get the data types of "paths". If "aggregations" is not null, each one of it correspond to one
   * in "paths". First get types locally and if some paths does not exists, pull them from other
   * nodes.
   *
   * @param aggregations nullable, when not null, correspond to "paths" one-to-one.
   * @return the left one of the pair is the column types (considering aggregation), the right one
   * of the pair is the measurement types (not considering aggregation)
   */
  public Pair<List<TSDataType>, List<TSDataType>> getSeriesTypesByPath(List<PartialPath> paths,
      List<String> aggregations) throws
      MetadataException {
    try {
      return getSeriesTypesByPathLocally(paths, aggregations);
    } catch (PathNotExistException e) {
      return getSeriesTypesByPathRemotely(paths, aggregations);
    }
  }

  /**
   * get data types of the given paths considering the aggregations from CMManger.
   *
   * @param aggregations nullable, when not null, correspond to "paths" one-to-one.
   * @return the left one of the pair is the column types (considering aggregation), the right one
   * of the pair is the measurement types (not considering aggregation)
   */
  private Pair<List<TSDataType>, List<TSDataType>> getSeriesTypesByPathLocally(
      List<PartialPath> paths,
      List<String> aggregations) throws MetadataException {
    List<TSDataType> measurementDataTypes = SchemaUtils.getSeriesTypesByPath(paths);
    // if the aggregation function is null, the type of column in result set
    // is equal to the real type of the measurement
    if (aggregations == null) {
      return new Pair<>(measurementDataTypes, measurementDataTypes);
    } else {
      // if the aggregation function is not null,
      // we should recalculate the type of column in result set
      List<TSDataType> columnDataTypes = SchemaUtils.getSeriesTypesByPaths(paths, aggregations);
      return new Pair<>(columnDataTypes, measurementDataTypes);
    }
  }

  /**
   * pull schemas from remote nodes and cache them, then get data types of the given paths
   * considering the aggregations from CMManger.
   *
   * @param aggregations nullable, when not null, correspond to "paths" one-to-one.
   * @return the left one of the pair is the column types (considering aggregation), the right one
   * of the pair is the measurement types (not considering aggregation)
   */
  private Pair<List<TSDataType>, List<TSDataType>> getSeriesTypesByPathRemotely(
      List<PartialPath> paths,
      List<String> aggregations) throws MetadataException {
    // pull schemas remotely and cache them
    ((CMManager) IoTDB.metaManager).pullTimeSeriesSchemas(paths, null);

    return getSeriesTypesByPathLocally(paths, aggregations);
  }

  /**
   * Get all devices after removing wildcards in the path
   *
   * @param originPath a path potentially with wildcard
   * @return all paths after removing wildcards in the path
   */
  public Set<PartialPath> getMatchedDevices(PartialPath originPath) throws MetadataException {
    // make sure this node knows all storage groups
    try {
      metaGroupMember.syncLeaderWithConsistencyCheck();
    } catch (CheckConsistencyException e) {
      throw new MetadataException(e);
    }
    // get all storage groups this path may belong to
    // the key is the storage group name and the value is the path to be queried with storage group
    // added, e.g:
    // "root.*" will be translated into:
    // "root.group1" -> "root.group1.*", "root.group2" -> "root.group2.*" ...
    Map<String, String> sgPathMap =
        IoTDB.metaManager.determineStorageGroup(originPath);
    logger.debug("The storage groups of path {} are {}", originPath, sgPathMap.keySet());
    Set<PartialPath> ret = getMatchedDevices(sgPathMap);
    logger.debug("The devices of path {} are {}", originPath, ret);

    return ret;
  }

  /**
   * Split the paths by the data group they belong to and query them from the groups separately.
   *
   * @param sgPathMap the key is the storage group name and the value is the path to be queried with
   * storage group added
   * @return a collection of all queried paths
   */
  private List<PartialPath> getMatchedPaths(Map<String, String> sgPathMap)
      throws MetadataException {
    List<PartialPath> result = new ArrayList<>();
    // split the paths by the data group they belong to
    Map<PartitionGroup, List<String>> groupPathMap = new HashMap<>();
    for (Entry<String, String> sgPathEntry : sgPathMap.entrySet()) {
      String storageGroupName = sgPathEntry.getKey();
      PartialPath pathUnderSG = new PartialPath(sgPathEntry.getValue());
      // find the data group that should hold the timeseries schemas of the storage group
      PartitionGroup partitionGroup = metaGroupMember.getPartitionTable().route(storageGroupName, 0);
      if (partitionGroup.contains(metaGroupMember.getThisNode())) {
        // this node is a member of the group, perform a local query after synchronizing with the
        // leader
        metaGroupMember.getLocalDataMember(partitionGroup.getHeader()).syncLeader();
        List<PartialPath> allTimeseriesName = IoTDB.metaManager.getAllTimeseriesPath(pathUnderSG);
        logger.debug("{}: get matched paths of {} locally, result {}", metaGroupMember.getName(),
            partitionGroup,
            allTimeseriesName);
        result.addAll(allTimeseriesName);
      } else {
        // batch the queries of the same group to reduce communication
        groupPathMap.computeIfAbsent(partitionGroup, p -> new ArrayList<>())
            .add(pathUnderSG.getFullPath());
      }
    }

    // query each data group separately
    for (Entry<PartitionGroup, List<String>> partitionGroupPathEntry : groupPathMap.entrySet()) {
      PartitionGroup partitionGroup = partitionGroupPathEntry.getKey();
      List<String> pathsToQuery = partitionGroupPathEntry.getValue();
      result.addAll(getMatchedPaths(partitionGroup, pathsToQuery));
    }

    return result;
  }

  private List<PartialPath> getMatchedPaths(PartitionGroup partitionGroup,
      List<String> pathsToQuery)
      throws MetadataException {
    // choose the node with lowest latency or highest throughput
    List<Node> coordinatedNodes = QueryCoordinator.getINSTANCE().reorderNodes(partitionGroup);
    for (Node node : coordinatedNodes) {
      try {
        List<PartialPath> paths = getMatchedPaths(node, partitionGroup.getHeader(), pathsToQuery);
        if (logger.isDebugEnabled()) {
          logger.debug("{}: get matched paths of {} and other {} paths from {} in {}, result {}",
              metaGroupMember.getName(), pathsToQuery.get(0), pathsToQuery.size() - 1, node,
              partitionGroup.getHeader(),
              paths);
        }
        if (paths != null) {
          // a non-null result contains correct result even if it is empty, so query next group
          return paths;
        }
      } catch (IOException | TException e) {
        throw new MetadataException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MetadataException(e);
      }
    }
    logger.warn("Cannot get paths of {} from {}", pathsToQuery, partitionGroup);
    return Collections.emptyList();
  }

  @SuppressWarnings("java:S1168") // null and empty list are different
  private List<PartialPath> getMatchedPaths(Node node, Node header, List<String> pathsToQuery)
      throws IOException, TException, InterruptedException {
    List<String> paths;
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncDataClient client = metaGroupMember.getClientProvider().getAsyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      paths = SyncClientAdaptor.getAllPaths(client, header,
          pathsToQuery);
    } else {
      SyncDataClient syncDataClient = metaGroupMember.getClientProvider().getSyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      paths = syncDataClient.getAllPaths(header, pathsToQuery);
      ClientUtils.putBackSyncClient(syncDataClient);
    }

    if (paths != null) {
      // paths may be empty, implying that the group does not contain matched paths, so we do not
      // need to query other nodes in the group
      List<PartialPath> partialPaths = new ArrayList<>();
      for (String path : paths) {
        try {
          partialPaths.add(new PartialPath(path));
        } catch (IllegalPathException e) {
          // ignore
        }
      }
      return partialPaths;
    } else {
      // a null implies a network failure, so we have to query other nodes in the group
      return null;
    }
  }

  /**
   * Split the paths by the data group they belong to and query them from the groups separately.
   *
   * @param sgPathMap the key is the storage group name and the value is the path to be queried with
   * storage group added
   * @return a collection of all queried devices
   */
  private Set<PartialPath> getMatchedDevices(Map<String, String> sgPathMap)
      throws MetadataException {
    Set<PartialPath> result = new HashSet<>();
    // split the paths by the data group they belong to
    Map<PartitionGroup, List<String>> groupPathMap = new HashMap<>();
    for (Entry<String, String> sgPathEntry : sgPathMap.entrySet()) {
      String storageGroupName = sgPathEntry.getKey();
      PartialPath pathUnderSG = new PartialPath(sgPathEntry.getValue());
      // find the data group that should hold the timeseries schemas of the storage group
      PartitionGroup partitionGroup = metaGroupMember.getPartitionTable().route(storageGroupName, 0);
      if (partitionGroup.contains(metaGroupMember.getThisNode())) {
        // this node is a member of the group, perform a local query after synchronizing with the
        // leader
        metaGroupMember.getLocalDataMember(partitionGroup.getHeader()).syncLeader();
        Set<PartialPath> allDevices = IoTDB.metaManager.getDevices(pathUnderSG);
        logger.debug("{}: get matched paths of {} locally, result {}", metaGroupMember.getName(),
            partitionGroup,
            allDevices);
        result.addAll(allDevices);
      } else {
        // batch the queries of the same group to reduce communication
        groupPathMap.computeIfAbsent(partitionGroup, p -> new ArrayList<>())
            .add(pathUnderSG.getFullPath());
      }
    }

    // query each data group separately
    for (Entry<PartitionGroup, List<String>> partitionGroupPathEntry : groupPathMap.entrySet()) {
      PartitionGroup partitionGroup = partitionGroupPathEntry.getKey();
      List<String> pathsToQuery = partitionGroupPathEntry.getValue();

      result.addAll(getMatchedDevices(partitionGroup, pathsToQuery));
    }

    return result;
  }

  private Set<PartialPath> getMatchedDevices(PartitionGroup partitionGroup,
      List<String> pathsToQuery)
      throws MetadataException {
    // choose the node with lowest latency or highest throughput
    List<Node> coordinatedNodes = QueryCoordinator.getINSTANCE().reorderNodes(partitionGroup);
    for (Node node : coordinatedNodes) {
      try {
        Set<String> paths = getMatchedDevices(node, partitionGroup.getHeader(), pathsToQuery);
        logger.debug("{}: get matched paths of {} from {}, result {}", metaGroupMember.getName(),
            partitionGroup,
            node, paths);
        if (paths != null) {
          // query next group
          Set<PartialPath> partialPaths = new HashSet<>();
          for (String path : paths) {
            partialPaths.add(new PartialPath(path));
          }
          return partialPaths;
        }
      } catch (IOException | TException e) {
        throw new MetadataException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MetadataException(e);
      }
    }
    logger.warn("Cannot get paths of {} from {}", pathsToQuery, partitionGroup);
    return Collections.emptySet();
  }

  private Set<String> getMatchedDevices(Node node, Node header, List<String> pathsToQuery)
      throws IOException, TException, InterruptedException {
    Set<String> paths;
    if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
      AsyncDataClient client = metaGroupMember.getClientProvider().getAsyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      paths = SyncClientAdaptor.getAllDevices(client, header,
          pathsToQuery);
    } else {
      SyncDataClient syncDataClient = metaGroupMember.getClientProvider().getSyncDataClient(node,
          RaftServer.getReadOperationTimeoutMS());
      paths = syncDataClient.getAllDevices(header, pathsToQuery);
      ClientUtils.putBackSyncClient(syncDataClient);
    }
    return paths;
  }

  /**
   * Get all paths after removing wildcards in the path
   *
   * @param originPath a path potentially with wildcard
   * @return all paths after removing wildcards in the path
   */
  public List<PartialPath> getMatchedPaths(PartialPath originPath) throws MetadataException {
    // make sure this node knows all storage groups
    try {
      metaGroupMember.syncLeaderWithConsistencyCheck();
    } catch (CheckConsistencyException e) {
      throw new MetadataException(e);
    }
    // get all storage groups this path may belong to
    // the key is the storage group name and the value is the path to be queried with storage group
    // added, e.g:
    // "root.*" will be translated into:
    // "root.group1" -> "root.group1.*", "root.group2" -> "root.group2.*" ...
    Map<String, String> sgPathMap =
        IoTDB.metaManager.determineStorageGroup(originPath);
    logger.debug("The storage groups of path {} are {}", originPath, sgPathMap.keySet());
    List<PartialPath> ret = getMatchedPaths(sgPathMap);
    logger.debug("The paths of path {} are {}", originPath, ret);
    return ret;
  }

  /**
   * Get all paths after removing wildcards in the path
   *
   * @param originalPaths, a list of paths, potentially with wildcard
   * @return a pair of path lists, the first are the existing full paths, the second are invalid
   * original paths
   */
  public Pair<List<PartialPath>, List<PartialPath>> getMatchedPaths(
      List<PartialPath> originalPaths) {
    ConcurrentSkipListSet<PartialPath> fullPaths = new ConcurrentSkipListSet<>();
    ConcurrentSkipListSet<PartialPath> nonExistPaths = new ConcurrentSkipListSet<>();
    ExecutorService getAllPathsService = Executors
        .newFixedThreadPool(metaGroupMember.getPartitionTable().getGlobalGroups().size());
    for (PartialPath pathStr : originalPaths) {
      getAllPathsService.submit(() -> {
        try {
          List<PartialPath> fullPathStrs = ((CMManager) IoTDB.metaManager).getMatchedPaths(pathStr);
          if (fullPathStrs.isEmpty()) {
            nonExistPaths.add(pathStr);
            logger.error("Path {} is not found.", pathStr);
          } else {
            fullPaths.addAll(fullPathStrs);
          }
        } catch (MetadataException e) {
          logger.error("Failed to get full paths of the prefix path: {} because", pathStr, e);
        }
      });
    }
    getAllPathsService.shutdown();
    try {
      getAllPathsService
          .awaitTermination(RaftServer.getReadOperationTimeoutMS(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Unexpected interruption when waiting for get all paths services to stop", e);
    }
    return new Pair<>(new ArrayList<>(fullPaths), new ArrayList<>(nonExistPaths));
  }

  /**
   * Get the local paths that match any path in "paths". The result is not deduplicated.
   *
   * @param paths paths potentially contain wildcards
   */
  public List<String> getAllPaths(List<String> paths) throws MetadataException {
    List<String> ret = new ArrayList<>();
    for (String path : paths) {
      IoTDB.metaManager.getAllTimeseriesPath(
          new PartialPath(path)).stream().map(PartialPath::getFullPath).forEach(ret::add);
    }
    return ret;
  }

  /**
   * Get the local devices that match any path in "paths". The result is deduplicated.
   *
   * @param paths paths potentially contain wildcards
   */
  public Set<String> getAllDevices(List<String> paths) throws MetadataException {
    Set<String> results = new HashSet<>();
    for (String path : paths) {
      IoTDB.metaManager.getAllTimeseriesPath(
          new PartialPath(path)).stream().map(PartialPath::getFullPath).forEach(results::add);
    }
    return results;
  }

  /**
   * Get the nodes of a prefix "path" at "nodeLevel". The method currently requires strong
   * consistency.
   *
   * @param path
   * @param nodeLevel
   */
  public List<String> getNodeList(String path, int nodeLevel)
      throws MetadataException {
    return IoTDB.metaManager.getNodesList(new PartialPath(path), nodeLevel).stream().map(PartialPath::getFullPath).collect(
        Collectors.toList());
  }

  public Set<String> getChildNodePathInNextLevel(String path)
      throws MetadataException {
    return IoTDB.metaManager.getChildNodePathInNextLevel(new PartialPath(path));
  }

  /**
   * Replace partial paths (paths not containing measurements), and abstract paths (paths containing
   * wildcards) with full paths.
   */
  public void convertToFullPaths(PhysicalPlan plan)
      throws PathNotExistException {
    Pair<List<PartialPath>, List<PartialPath>> getMatchedPathsRet =
        getMatchedPaths(plan.getPaths());
    List<PartialPath> fullPaths = getMatchedPathsRet.left;
    List<PartialPath> nonExistPath = getMatchedPathsRet.right;
    if (!nonExistPath.isEmpty()) {
      throw new PathNotExistException(nonExistPath.stream().map(PartialPath::getFullPath).collect(
          Collectors.toList()));
    }
    plan.setPaths(fullPaths);
  }
}