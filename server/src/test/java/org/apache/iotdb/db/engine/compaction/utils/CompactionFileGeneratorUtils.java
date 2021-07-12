package org.apache.iotdb.db.engine.compaction.utils;

import org.apache.iotdb.db.engine.modification.Deletion;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.chunk.ChunkWriterImpl;
import org.apache.iotdb.tsfile.write.chunk.IChunkWriter;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.writer.RestorableTsFileIOWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class CompactionFileGeneratorUtils {

  /**
   * Generate a new file. For each time series, insert a point (+1 for each point) into the file
   * from the start time util each sequence of the last file meets the target Chunk and Page size,
   * the value is also equal to the time
   *
   * @param fullPaths Set(fullPath)
   * @param chunkPagePointsNum chunk->page->points
   * @param startTime The startTime to write
   * @param newTsFileResource The tsfile to write
   */
  private static void generateNewTsFile(
      Set<String> fullPaths,
      List<List<Long>> chunkPagePointsNum,
      long startTime,
      TsFileResource newTsFileResource)
      throws IOException, IllegalPathException {
    // disable auto page seal and seal page manually
    int prevMaxNumberOfPointsInPage =
        TSFileDescriptor.getInstance().getConfig().getMaxNumberOfPointsInPage();
    TSFileDescriptor.getInstance().getConfig().setMaxNumberOfPointsInPage(Integer.MAX_VALUE);

    RestorableTsFileIOWriter writer = new RestorableTsFileIOWriter(newTsFileResource.getTsFile());
    Map<String, List<String>> deviceMeasurementMap = new HashMap<>();
    for (String fullPath : fullPaths) {
      PartialPath partialPath = new PartialPath(fullPath);
      List<String> sensors =
          deviceMeasurementMap.computeIfAbsent(partialPath.getDevice(), (s) -> new ArrayList<>());
      sensors.add(partialPath.getMeasurement());
    }
    for (Entry<String, List<String>> deviceMeasurementEntry : deviceMeasurementMap.entrySet()) {
      String device = deviceMeasurementEntry.getKey();
      writer.startChunkGroup(device);
      for (String sensor : deviceMeasurementEntry.getValue()) {
        for (List<Long> chunk : chunkPagePointsNum) {
          IChunkWriter chunkWriter =
              new ChunkWriterImpl(new MeasurementSchema(sensor, TSDataType.INT64), true);
          for (Long page : chunk) {
            for (long i = 0; i < page; i++) {
              chunkWriter.write(startTime + i, startTime + i, false);
              newTsFileResource.updateStartTime(device, startTime + i);
              newTsFileResource.updateEndTime(device, startTime + i);
            }
            chunkWriter.sealCurrentPage();
          }
          chunkWriter.writeToFileWriter(writer);
        }
      }
      writer.endChunkGroup();
    }

    TSFileDescriptor.getInstance()
        .getConfig()
        .setMaxNumberOfPointsInPage(prevMaxNumberOfPointsInPage);
  }

  /**
   * Generate mods files according to toDeleteTimeseriesAndTime for corresponding
   * targetTsFileResource
   *
   * @param toDeleteTimeseriesAndTime The timeseries and time to be deleted, Map(fullPath,
   *     (startTime, endTime))
   * @param targetTsFileResource The tsfile to be deleted
   * @param isCompactionMods Generate *.compaction. or generate .compaction.mods
   */
  private static void generateMods(
      Map<String, Pair<Long, Long>> toDeleteTimeseriesAndTime,
      TsFileResource targetTsFileResource,
      boolean isCompactionMods)
      throws IllegalPathException, IOException {
    ModificationFile modificationFile;
    if (isCompactionMods) {
      modificationFile = ModificationFile.getCompactionMods(targetTsFileResource);
    } else {
      modificationFile = ModificationFile.getNormalMods(targetTsFileResource);
    }
    for (Entry<String, Pair<Long, Long>> toDeleteTimeseriesAndTimeEntry :
        toDeleteTimeseriesAndTime.entrySet()) {
      String fullPath = toDeleteTimeseriesAndTimeEntry.getKey();
      Pair<Long, Long> startTimeEndTime = toDeleteTimeseriesAndTimeEntry.getValue();
      Deletion deletion =
          new Deletion(
              new PartialPath(fullPath),
              Long.MAX_VALUE,
              startTimeEndTime.left,
              startTimeEndTime.right);
      modificationFile.write(deletion);
    }
    modificationFile.close();
  }
}