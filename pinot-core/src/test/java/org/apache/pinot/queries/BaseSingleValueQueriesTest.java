/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.queries;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.segment.local.indexsegment.immutable.ImmutableSegmentLoader;
import org.apache.pinot.segment.local.segment.creator.impl.SegmentIndexCreationDriverImpl;
import org.apache.pinot.segment.local.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.segment.spi.ImmutableSegment;
import org.apache.pinot.segment.spi.IndexSegment;
import org.apache.pinot.segment.spi.creator.SegmentGeneratorConfig;
import org.apache.pinot.segment.spi.creator.SegmentIndexCreationDriver;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.config.table.ingestion.IngestionConfig;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;

import static org.testng.Assert.assertNotNull;


/**
 * The <code>BaseSingleValueQueriesTest</code> class sets up the index segment for the single-value queries test.
 * <p>There are totally 18 columns, 30000 records inside the original Avro file where 11 columns are selected to build
 * the index segment. Selected columns information are as following:
 * <ul>
 *   ColumnName, FieldType, DataType, Cardinality, IsSorted, HasInvertedIndex
 *   <li>column1, METRIC, INT, 6582, F, F</li>
 *   <li>column3, METRIC, INT, 21910, F, F</li>
 *   <li>column5, DIMENSION, STRING, 1, T, F</li>
 *   <li>column6, DIMENSION, INT, 608, F, T</li>
 *   <li>column7, DIMENSION, INT, 146, F, T</li>
 *   <li>column9, DIMENSION, INT, 1737, F, F</li>
 *   <li>column11, DIMENSION, STRING, 5, F, T</li>
 *   <li>column12, DIMENSION, STRING, 5, F, F</li>
 *   <li>column17, METRIC, INT, 24, F, T</li>
 *   <li>column18, METRIC, INT, 1440, F, T</li>
 *   <li>daysSinceEpoch, TIME, INT, 2, T, F</li>
 * </ul>
 */
public abstract class BaseSingleValueQueriesTest extends BaseQueriesTest {
  private static final File INDEX_DIR = new File(FileUtils.getTempDirectory(), "SingleValueQueriesTest");
  private static final String AVRO_DATA = "data" + File.separator + "test_data-sv.avro";
  protected static final String RAW_TABLE_NAME = "testTable";
  private static final String SEGMENT_NAME = "testTable_126164076_167572854";

  //@formatter:off
  protected static final Schema SCHEMA = new Schema.SchemaBuilder()
      .setSchemaName(RAW_TABLE_NAME)
      .addMetric("column1", DataType.INT)
      .addMetric("column3", DataType.INT)
      .addSingleValueDimension("column5", DataType.STRING)
      .addSingleValueDimension("column6", DataType.INT)
      .addSingleValueDimension("column7", DataType.INT)
      .addSingleValueDimension("column9", DataType.INT)
      .addSingleValueDimension("column11", DataType.STRING)
      .addSingleValueDimension("column12", DataType.STRING)
      .addMetric("column17", DataType.INT)
      .addMetric("column18", DataType.INT)
      .addDateTime("daysSinceEpoch", DataType.INT, "EPOCH|DAYS", "1:DAYS")
      .build();
  protected static final TableConfig TABLE_CONFIG = new TableConfigBuilder(TableType.OFFLINE)
      .setTableName(RAW_TABLE_NAME)
      .setTimeColumnName("daysSinceEpoch")
      .setInvertedIndexColumns(List.of("column6", "column7", "column11", "column17", "column18"))
      .build();
  static {
    // The segment generation code in SegmentColumnarIndexCreator will throw exception if start and end time in time
    // column are not in acceptable range. For this test, we first need to fix the input avro data to have the time
    // column values in allowed range. Until then, the check is explicitly disabled.
    IngestionConfig ingestionConfig = new IngestionConfig();
    ingestionConfig.setSegmentTimeValueCheck(false);
    ingestionConfig.setRowTimeValueCheck(false);
    TABLE_CONFIG.setIngestionConfig(ingestionConfig);
  }
  protected static final String FILTER =
      " WHERE column1 > 100000000"
      + " AND column3 BETWEEN 20000000 AND 1000000000"
      + " AND column5 = 'gFuH'"
      + " AND (column6 < 500000000 OR column11 NOT IN ('t', 'P'))"
      + " AND daysSinceEpoch = 126164076";
  //@formatter:on

  private IndexSegment _indexSegment;
  // Contains 2 identical index segments.
  private List<IndexSegment> _indexSegments;

  @BeforeTest
  public void buildSegment()
      throws Exception {
    FileUtils.deleteQuietly(INDEX_DIR);

    // Get resource file path.
    URL resource = getClass().getClassLoader().getResource(AVRO_DATA);
    assertNotNull(resource);
    String filePath = resource.getFile();

    // Create the segment generator config.
    SegmentGeneratorConfig segmentGeneratorConfig = new SegmentGeneratorConfig(TABLE_CONFIG, SCHEMA);
    segmentGeneratorConfig.setInputFilePath(filePath);
    segmentGeneratorConfig.setTableName(RAW_TABLE_NAME);
    segmentGeneratorConfig.setOutDir(INDEX_DIR.getAbsolutePath());

    // Build the index segment.
    SegmentIndexCreationDriver driver = new SegmentIndexCreationDriverImpl();
    driver.init(segmentGeneratorConfig);
    driver.build();
  }

  @BeforeClass
  public void loadSegment()
      throws Exception {
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(TABLE_CONFIG, SCHEMA);
    ImmutableSegment immutableSegment =
        ImmutableSegmentLoader.load(new File(INDEX_DIR, SEGMENT_NAME), indexLoadingConfig);
    _indexSegment = immutableSegment;
    _indexSegments = Arrays.asList(immutableSegment, immutableSegment);
  }

  @AfterClass
  public void destroySegment() {
    _indexSegment.destroy();
  }

  @AfterTest
  public void deleteSegment() {
    FileUtils.deleteQuietly(INDEX_DIR);
  }

  @Override
  protected String getFilter() {
    return FILTER;
  }

  @Override
  protected IndexSegment getIndexSegment() {
    return _indexSegment;
  }

  @Override
  protected List<IndexSegment> getIndexSegments() {
    return _indexSegments;
  }
}
