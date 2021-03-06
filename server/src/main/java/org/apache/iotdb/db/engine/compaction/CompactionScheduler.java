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

package org.apache.iotdb.db.engine.compaction;

import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.compaction.task.AbstractCompactionTask;
import org.apache.iotdb.db.engine.compaction.task.InnerSpaceCompactionTask;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.engine.storagegroup.TsFileResourceList;
import org.apache.iotdb.db.engine.storagegroup.TsFileResourceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class CompactionScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(CompactionScheduler.class);
  private static AtomicInteger currentTaskNum = new AtomicInteger(0);
  private static IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  public static void compactionSchedule(
      TsFileResourceManager tsFileResourceManager, long timePartition) {
    if (currentTaskNum.get() >= config.getConcurrentCompactionThread()) {
      return;
    }
    tsFileResourceManager.readLock();
    try {
      TsFileResourceList sequenceFileList =
          tsFileResourceManager.getSequenceListByTimePartition(timePartition);
      TsFileResourceList unsequenceFileList =
          tsFileResourceManager.getUnsequenceListByTimePartition(timePartition);
      CompactionPriority compactionPriority = config.getCompactionPriority();
      if (compactionPriority == CompactionPriority.BALANCE) {
        boolean taskSubmitted = false;
        int concurrentCompactionThread = config.getConcurrentCompactionThread();
        while (taskSubmitted && currentTaskNum.get() < concurrentCompactionThread) {
          taskSubmitted =
              tryToSubmitInnerSpaceCompactionTask(
                  tsFileResourceManager.getStorageGroupName(),
                  timePartition,
                  sequenceFileList,
                  true,
                  InnerSpaceCompactionTask.class);
          taskSubmitted =
              tryToSubmitInnerSpaceCompactionTask(
                      tsFileResourceManager.getStorageGroupName(),
                      timePartition,
                      unsequenceFileList,
                      false,
                      InnerSpaceCompactionTask.class)
                  | taskSubmitted;
          taskSubmitted =
              tryToSubmitCrossSpaceCompactionTask(
                      tsFileResourceManager.getStorageGroupName(),
                      timePartition,
                      sequenceFileList,
                      unsequenceFileList)
                  | taskSubmitted;
        }
      } else if (compactionPriority == CompactionPriority.INNER_CROSS) {
        tryToSubmitInnerSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            sequenceFileList,
            true,
            InnerSpaceCompactionTask.class);
        tryToSubmitInnerSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            unsequenceFileList,
            false,
            InnerSpaceCompactionTask.class);
        tryToSubmitCrossSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            sequenceFileList,
            unsequenceFileList);
      } else if (compactionPriority == CompactionPriority.CROSS_INNER) {
        tryToSubmitCrossSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            sequenceFileList,
            unsequenceFileList);
        tryToSubmitInnerSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            sequenceFileList,
            true,
            InnerSpaceCompactionTask.class);
        tryToSubmitInnerSpaceCompactionTask(
            tsFileResourceManager.getStorageGroupName(),
            timePartition,
            unsequenceFileList,
            false,
            InnerSpaceCompactionTask.class);
      }
    } finally {
      tsFileResourceManager.readUnlock();
    }
  }

  public static boolean tryToSubmitInnerSpaceCompactionTask(
      String storageGroup,
      long timePartition,
      TsFileResourceList tsFileResources,
      boolean isSequence,
      Class clazz) {
    boolean taskSubmitted = false;
    TsFileResourceList selectedFileList = new TsFileResourceList();
    long selectedFileSize = 0L;
    long targetCompactionFileSize = config.getTargetCompactionFileSize();
    boolean enableSeqSpaceCompaction = config.isEnableSeqSpaceCompaction();
    boolean enableUnseqSpaceCompaction = config.isEnableUnseqSpaceCompaction();
    int concurrentCompactionThread = config.getConcurrentCompactionThread();
    // this iterator traverses the list in reverse order
    Iterator<TsFileResource> iterator = tsFileResources.reverseIterator();
    Constructor constructor = null;
    try {
      constructor =
          clazz.getConstructor(TsFileResourceList.class, Boolean.class, AtomicInteger.class);
    } catch (NoSuchMethodException e) {
      LOGGER.warn(e.getMessage(), e);
      return false;
    }
    while (iterator.hasNext()) {
      TsFileResource currentFile = iterator.next();
      if ((currentTaskNum.get() >= concurrentCompactionThread)
          || (!enableSeqSpaceCompaction && isSequence)
          || (!enableUnseqSpaceCompaction && !isSequence)) {
        return taskSubmitted;
      }
      if (currentFile.getTsFileSize() > targetCompactionFileSize
          || currentFile.isMerging()
          || !currentFile.isClosed()) {
        selectedFileList.clear();
        continue;
      }
      selectedFileList.add(currentFile);
      selectedFileSize += currentFile.getTsFileSize();
      if (selectedFileSize > targetCompactionFileSize) {
        // submit the task
        try {
          InnerSpaceCompactionTask innerSpaceCompactionTask =
              (InnerSpaceCompactionTask)
                  constructor.newInstance(selectedFileList, isSequence, currentTaskNum);
          CompactionTaskManager.getInstance()
              .submitTask(storageGroup, timePartition, innerSpaceCompactionTask);
          currentTaskNum.incrementAndGet();
          selectedFileList = new TsFileResourceList();
          selectedFileSize = 0L;
        } catch (Exception e) {
          LOGGER.warn(e.getMessage(), e);
          return false;
        }
      }
    }
    // if some files are selected but the total size is smaller than target size, submit a task
    if (selectedFileList.size() > 0) {
      AbstractCompactionTask innerSpaceCompactionTask =
          new InnerSpaceCompactionTask(selectedFileList, isSequence, currentTaskNum);
      CompactionTaskManager.getInstance()
          .submitTask(storageGroup, timePartition, innerSpaceCompactionTask);
      currentTaskNum.incrementAndGet();
    }
    return taskSubmitted;
  }

  private static boolean tryToSubmitCrossSpaceCompactionTask(
      String storageGroup,
      long timePartition,
      TsFileResourceList sequenceFileList,
      TsFileResourceList unsequenceFileList) {
    return false;
  }
}
