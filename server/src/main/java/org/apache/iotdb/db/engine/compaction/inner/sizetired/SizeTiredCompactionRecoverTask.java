package org.apache.iotdb.db.engine.compaction.inner.sizetired;

import org.apache.iotdb.db.engine.compaction.inner.AbstractInnerSpaceCompactionRecoverTask;
import org.apache.iotdb.db.engine.compaction.inner.utils.CompactionLogAnalyzer;
import org.apache.iotdb.db.engine.compaction.inner.utils.CompactionLogger;
import org.apache.iotdb.db.engine.compaction.inner.utils.CompactionUtils;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.engine.storagegroup.TsFileResourceList;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.tsfile.write.writer.RestorableTsFileIOWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SizeTiredCompactionRecoverTask extends SizeTiredCompactionTask {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AbstractInnerSpaceCompactionRecoverTask.class);
  protected File compactionLogFile;
  protected String storageGroupDir;
  protected TsFileResourceList tsFileResourceList;
  protected List<TsFileResource> recoverTsFileResources;

  public SizeTiredCompactionRecoverTask(
      String storageGroupName,
      String virtualStorageGroup,
      long timePartition,
      File compactionLogFile,
      String storageGroupDir,
      TsFileResourceList tsFileResourceList,
      List<TsFileResource> recoverTsFileResources,
      boolean sequence) {
    super(storageGroupName, virtualStorageGroup, timePartition, tsFileResourceList, null, sequence);
    this.compactionLogFile = compactionLogFile;
    this.storageGroupDir = storageGroupDir;
    this.tsFileResourceList = tsFileResourceList;
    this.recoverTsFileResources = recoverTsFileResources;
  }

  public void doCompaction() {
    // read log -> Set<Device> -> doCompaction -> clear
    try {
      if (compactionLogFile.exists()) {
        CompactionLogAnalyzer logAnalyzer = new CompactionLogAnalyzer(compactionLogFile);
        logAnalyzer.analyze();
        Set<String> deviceSet = logAnalyzer.getDeviceSet();
        List<String> sourceFileList = logAnalyzer.getSourceFiles();
        long offset = logAnalyzer.getOffset();
        String targetFile = logAnalyzer.getTargetFile();
        boolean isSeq = logAnalyzer.isSeq();
        if (targetFile == null || sourceFileList.isEmpty()) {
          return;
        }
        File target = new File(targetFile);
        if (deviceSet.isEmpty()) {
          // if not in compaction, just delete the target file
          if (target.exists()) {
            Files.delete(target.toPath());
          }
          return;
        }
        // get tsfile resource from list, as they have been recovered in StorageGroupProcessor
        TsFileResource targetResource = getRecoverTsFileResource(targetFile);
        List<TsFileResource> sourceTsFileResources = new ArrayList<>();

        tsFileResourceList.writeLock();
        try {
          for (String file : sourceFileList) {
            // get tsfile resource from list, as they have been recovered in StorageGroupProcessor
            TsFileResource resource = getSourceTsFile(file);
            resource.setMerging(true);
            sourceTsFileResources.add(resource);
          }
        } finally {
          tsFileResourceList.writeUnlock();
        }
        try {
          RestorableTsFileIOWriter writer = new RestorableTsFileIOWriter(target);
          // if not complete compaction, resume merge
          if (writer.hasCrashed()) {
            if (offset > 0) {
              writer.getIOWriterOut().truncate(offset - 1);
            }
            writer.close();
            CompactionLogger compactionLogger =
                new CompactionLogger(storageGroupDir, storageGroupName);
            CompactionUtils.compact(
                targetResource,
                sourceTsFileResources,
                storageGroupName,
                compactionLogger,
                deviceSet,
                isSeq);
            // complete compaction and delete source file
            tsFileResourceList.writeLock();
            try {
              if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(
                    String.format("%s [Compaction] abort", storageGroupName));
              }
              tsFileResourceList.insertBefore(sourceTsFileResources.get(0), targetResource);
              for (TsFileResource resource : tsFileResourceList) {
                tsFileResourceList.remove(resource);
              }
            } finally {
              tsFileResourceList.writeUnlock();
            }
            CompactionUtils.deleteTsFilesInDisk(sourceTsFileResources, storageGroupName);
            renameLevelFilesMods(sourceTsFileResources, targetResource);
            compactionLogger.close();
          } else {
            writer.close();
          }
        } finally {
          tsFileResourceList.writeLock();
          try {
            for (TsFileResource resource : sourceTsFileResources) {
              resource.setMerging(false);
            }
          } finally {
            tsFileResourceList.writeUnlock();
          }
        }
      }
    } catch (IOException | IllegalPathException | InterruptedException e) {
      LOGGER.error("recover inner space compaction error", e);
    } finally {
      if (compactionLogFile.exists()) {
        try {
          Files.delete(compactionLogFile.toPath());
        } catch (IOException e) {
          LOGGER.error("delete inner space compaction log file error", e);
        }
      }
    }
  }

  private TsFileResource getRecoverTsFileResource(String filePath) throws IOException {
    for (TsFileResource tsFileResource : recoverTsFileResources) {
      if (Files.isSameFile(tsFileResource.getTsFile().toPath(), new File(filePath).toPath())) {
        return tsFileResource;
      }
    }
    LOGGER.error("cannot get tsfile resource path: {}", filePath);
    throw new IOException();
  }

  private TsFileResource getSourceTsFile(String filename) {
    tsFileResourceList.readLock();
    try {
      File fileToGet = new File(filename);
      for (TsFileResource resource : tsFileResourceList) {
        if (Files.isSameFile(resource.getTsFile().toPath(), fileToGet.toPath())) {
          return resource;
        }
      }
      LOGGER.error("cannot get tsfile resource path: {}", filename);
      return null;
    } catch (IOException e) {
      LOGGER.error("cannot get tsfile resource path: {}", filename);
      return null;
    } finally {
      tsFileResourceList.readUnlock();
    }
  }
}
