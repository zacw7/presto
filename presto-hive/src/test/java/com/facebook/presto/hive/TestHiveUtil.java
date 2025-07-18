/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.authentication.NoHdfsAuthentication;
import com.facebook.presto.hive.metastore.Storage;
import com.facebook.presto.hive.metastore.StorageFormat;
import com.facebook.presto.hive.metastore.file.FileHiveMetastore;
import com.facebook.presto.spi.PrestoException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slices;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat;
import org.apache.hadoop.hive.serde2.thrift.ThriftDeserializer;
import org.apache.hadoop.hive.serde2.thrift.test.IntString;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hudi.hadoop.realtime.HoodieRealtimeFileSplit;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.testng.annotations.Test;

import java.io.File;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TimeZone;

import static com.facebook.airlift.testing.Assertions.assertInstanceOf;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.DateType.DATE;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.hive.HiveStorageFormat.ORC;
import static com.facebook.presto.hive.HiveStorageFormat.PARQUET;
import static com.facebook.presto.hive.HiveStorageFormat.TEXTFILE;
import static com.facebook.presto.hive.HiveTestUtils.SESSION;
import static com.facebook.presto.hive.HiveUtil.CLIENT_TAGS_DELIMITER;
import static com.facebook.presto.hive.HiveUtil.CUSTOM_FILE_SPLIT_CLASS_KEY;
import static com.facebook.presto.hive.HiveUtil.PRESTO_CLIENT_INFO;
import static com.facebook.presto.hive.HiveUtil.PRESTO_CLIENT_TAGS;
import static com.facebook.presto.hive.HiveUtil.PRESTO_METASTORE_HEADER;
import static com.facebook.presto.hive.HiveUtil.PRESTO_QUERY_ID;
import static com.facebook.presto.hive.HiveUtil.PRESTO_QUERY_SOURCE;
import static com.facebook.presto.hive.HiveUtil.PRESTO_USER_NAME;
import static com.facebook.presto.hive.HiveUtil.buildDirectoryContextProperties;
import static com.facebook.presto.hive.HiveUtil.checkRowIDPartitionComponent;
import static com.facebook.presto.hive.HiveUtil.getDeserializer;
import static com.facebook.presto.hive.HiveUtil.parseHiveTimestamp;
import static com.facebook.presto.hive.HiveUtil.parsePartitionValue;
import static com.facebook.presto.hive.HiveUtil.shouldUseRecordReaderFromInputFormat;
import static com.facebook.presto.hive.metastore.MetastoreUtil.getMetastoreHeaders;
import static com.facebook.presto.hive.metastore.MetastoreUtil.toPartitionNamesAndValues;
import static com.facebook.presto.hive.metastore.MetastoreUtil.toPartitionValues;
import static com.facebook.presto.hive.util.HudiRealtimeSplitConverter.HUDI_BASEPATH_KEY;
import static com.facebook.presto.hive.util.HudiRealtimeSplitConverter.HUDI_DELTA_FILEPATHS_KEY;
import static com.facebook.presto.hive.util.HudiRealtimeSplitConverter.HUDI_MAX_COMMIT_TIME_KEY;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_CLASS;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_FORMAT;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LIB;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestHiveUtil
{
    public static FileHiveMetastore createTestingFileHiveMetastore(File catalogDirectory)
    {
        HiveClientConfig hiveClientConfig = new HiveClientConfig();
        MetastoreClientConfig metastoreClientConfig = new MetastoreClientConfig();
        HdfsConfiguration hdfsConfiguration = new HiveHdfsConfiguration(new HdfsConfigurationInitializer(hiveClientConfig, metastoreClientConfig), ImmutableSet.of(), hiveClientConfig);
        HdfsEnvironment hdfsEnvironment = new HdfsEnvironment(hdfsConfiguration, metastoreClientConfig, new NoHdfsAuthentication());
        return new FileHiveMetastore(hdfsEnvironment, catalogDirectory.toURI().toString(), "test");
    }

    @Test
    public void testCheckRowIDPartitionComponent_noRowID()
    {
        HiveColumnHandle handle = HiveColumnHandle.pathColumnHandle();
        List<HiveColumnHandle> columns = ImmutableList.of(handle);
        checkRowIDPartitionComponent(columns, Optional.empty());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testCheckRowIDPartitionComponent_Missing()
    {
        HiveColumnHandle handle = HiveColumnHandle.rowIdColumnHandle();
        List<HiveColumnHandle> columns = ImmutableList.of(handle);
        checkRowIDPartitionComponent(columns, Optional.empty());
    }

    @Test
    public void testCheckRowIDPartitionComponent_rowID()
    {
        HiveColumnHandle handle = HiveColumnHandle.rowIdColumnHandle();
        List<HiveColumnHandle> columns = ImmutableList.of(handle);
        checkRowIDPartitionComponent(columns, Optional.of(new byte[0]));
    }

    @Test
    public void testParseHiveTimestamp()
    {
        DateTime time = new DateTime(2011, 5, 6, 7, 8, 9, 123, nonDefaultTimeZone());
        assertEquals(parse(time, "yyyy-MM-dd HH:mm:ss"), unixTime(time, 0));
        assertEquals(parse(time, "yyyy-MM-dd HH:mm:ss.S"), unixTime(time, 1));
        assertEquals(parse(time, "yyyy-MM-dd HH:mm:ss.SSS"), unixTime(time, 3));
        assertEquals(parse(time, "yyyy-MM-dd HH:mm:ss.SSSSSSS"), unixTime(time, 6));
        assertEquals(parse(time, "yyyy-MM-dd HH:mm:ss.SSSSSSSSS"), unixTime(time, 7));
    }

    @Test
    public void testGetThriftDeserializer()
    {
        Properties schema = new Properties();
        schema.setProperty(SERIALIZATION_LIB, ThriftDeserializer.class.getName());
        schema.setProperty(SERIALIZATION_CLASS, IntString.class.getName());
        schema.setProperty(SERIALIZATION_FORMAT, TBinaryProtocol.class.getName());

        assertInstanceOf(getDeserializer(new Configuration(false), schema), ThriftDeserializer.class);
    }

    @Test
    public void testToPartitionValues()
            throws MetaException
    {
        assertToPartitionValues("ds=2015-12-30/event_type=QueryCompletion");
        assertToPartitionValues("ds=2015-12-30");
        assertToPartitionValues("a=1/b=2/c=3");
        assertToPartitionValues("a=1");
        assertToPartitionValues("pk=!@%23$%25%5E&%2A()%2F%3D");
        assertToPartitionValues("pk=__HIVE_DEFAULT_PARTITION__");
    }

    @Test
    public void testToPartitionNamesAndValues()
            throws MetaException
    {
        List<String> expectedKeyList1 = new ArrayList<>();
        expectedKeyList1.add("ds");
        expectedKeyList1.add("event_type");
        assertToPartitionNamesAndValues("ds=2015-12-30/event_type=QueryCompletion", expectedKeyList1);

        List<String> expectedKeyList2 = new ArrayList<>();
        expectedKeyList2.add("a");
        expectedKeyList2.add("b");
        expectedKeyList2.add("c");
        assertToPartitionNamesAndValues("a=1/b=2/c=3", expectedKeyList2);

        List<String> expectedKeyList3 = new ArrayList<>();
        expectedKeyList3.add("pk");
        assertToPartitionNamesAndValues("pk=!@%23$%25%5E&%2A()%2F%3D", expectedKeyList3);

        List<String> expectedKeyList4 = new ArrayList<>();
        expectedKeyList4.add("pk");
        assertToPartitionNamesAndValues("pk=__HIVE_DEFAULT_PARTITION__", expectedKeyList4);
    }

    @Test
    public void testShouldUseRecordReaderFromInputFormat()
    {
        StorageFormat hudiStorageFormat = StorageFormat.create("parquet.hive.serde.ParquetHiveSerDe", "org.apache.hudi.hadoop.HoodieParquetInputFormat", "");
        assertFalse(shouldUseRecordReaderFromInputFormat(new Configuration(), new Storage(hudiStorageFormat, "test", Optional.empty(), true, ImmutableMap.of(), ImmutableMap.of()), ImmutableMap.of()));

        StorageFormat hudiRealtimeStorageFormat = StorageFormat.create("parquet.hive.serde.ParquetHiveSerDe", "org.apache.hudi.hadoop.realtime.HoodieParquetRealtimeInputFormat", "");
        Map<String, String> customSplitInfo = ImmutableMap.of(
                CUSTOM_FILE_SPLIT_CLASS_KEY, HoodieRealtimeFileSplit.class.getName(),
                HUDI_BASEPATH_KEY, "/test/file.parquet",
                HUDI_DELTA_FILEPATHS_KEY, "/test/.file_100.log",
                HUDI_MAX_COMMIT_TIME_KEY, "100");
        assertTrue(shouldUseRecordReaderFromInputFormat(new Configuration(), new Storage(hudiRealtimeStorageFormat, "test", Optional.empty(), true, ImmutableMap.of(), ImmutableMap.of()), customSplitInfo));
    }

    @Test
    public void testBuildDirectoryContextProperties()
    {
        Map<String, String> additionalProperties = buildDirectoryContextProperties(SESSION);
        assertEquals(additionalProperties.get(PRESTO_QUERY_ID), SESSION.getQueryId());
        assertEquals(Optional.ofNullable(additionalProperties.get(PRESTO_QUERY_SOURCE)), SESSION.getSource());
        assertEquals(Optional.ofNullable(additionalProperties.get(PRESTO_CLIENT_INFO)), SESSION.getClientInfo());
        assertEquals(additionalProperties.get(PRESTO_USER_NAME), SESSION.getUser());
        assertEquals(Optional.ofNullable(additionalProperties.get(PRESTO_METASTORE_HEADER)), getMetastoreHeaders(SESSION));
        assertEquals(Arrays.stream(additionalProperties.get(PRESTO_CLIENT_TAGS).split(CLIENT_TAGS_DELIMITER)).collect(toImmutableSet()), SESSION.getClientTags());
    }

    @Test
    public void testParsePartitionValue()
    {
        Object prestoValue = parsePartitionValue("p=1970-01-02", "1970-01-02", DATE, ZoneId.of(TimeZone.getDefault().getID())).getValue();
        assertEquals(Long.parseLong(String.valueOf(prestoValue)), 1L);

        prestoValue = parsePartitionValue("p=1234", "1234", INTEGER, ZoneId.of(TimeZone.getDefault().getID())).getValue();
        assertEquals(Integer.parseInt(String.valueOf(prestoValue)), 1234);

        prestoValue = parsePartitionValue("p=true", "true", BOOLEAN, ZoneId.of(TimeZone.getDefault().getID())).getValue();
        assertTrue(Boolean.parseBoolean(String.valueOf(prestoValue)));

        prestoValue = parsePartitionValue("p=USA", "USA", VARCHAR, ZoneId.of(TimeZone.getDefault().getID())).getValue();
        assertEquals(prestoValue, Slices.utf8Slice("USA"));
    }

    @Test
    public void testGetInputFormatValidInput()
    {
        Configuration configuration = new Configuration();
        String inputFormatName = ORC.getInputFormat();
        String serDe = ORC.getSerDe();
        boolean symlinkTarget = false;

        InputFormat<?, ?> inputFormat = HiveUtil.getInputFormat(configuration, inputFormatName, serDe, symlinkTarget);
        assertNotNull(inputFormat, "InputFormat should not be null for valid input");
        assertEquals(inputFormat.getClass().getName(), ORC.getInputFormat());
    }

    @Test
    public void testGetInputFormatInvalidInputFormatName()
    {
        Configuration configuration = new Configuration();
        String inputFormatName = "invalid.InputFormatName";
        String serDe = ORC.getSerDe();
        boolean symlinkTarget = false;

        assertThatThrownBy(() -> HiveUtil.getInputFormat(configuration, inputFormatName, serDe, symlinkTarget))
                .isInstanceOf(PrestoException.class)
                .hasStackTraceContaining("Unable to create input format invalid.InputFormatName");
    }

    @Test
    public void testGetInputFormatMissingSerDeForSymlinkTextInputFormat()
    {
        Configuration configuration = new Configuration();
        String inputFormatName = SymlinkTextInputFormat.class.getName();
        String serDe = null;
        boolean symlinkTarget = true;

        assertThatThrownBy(() -> HiveUtil.getInputFormat(configuration, inputFormatName, serDe, symlinkTarget))
                .isInstanceOf(PrestoException.class)
                .hasStackTraceContaining("Missing SerDe for SymlinkTextInputFormat");
    }

    @Test
    public void testGetInputFormatUnsupportedSerDeForSymlinkTextInputFormat()
    {
        Configuration configuration = new Configuration();
        String inputFormatName = SymlinkTextInputFormat.class.getName();
        String serDe = "unsupported.SerDe";
        boolean symlinkTarget = true;

        assertThatThrownBy(() -> HiveUtil.getInputFormat(configuration, inputFormatName, serDe, symlinkTarget))
                .isInstanceOf(PrestoException.class)
                .hasStackTraceContaining("Unsupported SerDe for SymlinkTextInputFormat: unsupported.SerDe");
    }

    @Test
    public void testGetInputFormatForAllSupportedSerDesForSymlinkTextInputFormat()
    {
        Configuration configuration = new Configuration();
        boolean symlinkTarget = true;

        /*
         * https://github.com/apache/hive/blob/b240eb3266d4736424678d6c71c3c6f6a6fdbf38/ql/src/java/org/apache/hadoop/hive/ql/io/SymlinkTextInputFormat.java#L47-L52
         * According to Hive implementation of SymlinkInputFormat, The target input data should be in TextInputFormat.
         *
         * But another common use-case of Symlink Tables is to read Delta Lake Symlink Tables with target input data as MapredParquetInputFormat
         * https://docs.delta.io/latest/presto-integration.html
         */
        List<HiveStorageFormat> supportedFormats = ImmutableList.of(PARQUET, TEXTFILE);

        for (HiveStorageFormat hiveStorageFormat : supportedFormats) {
            String inputFormatName = SymlinkTextInputFormat.class.getName();
            String serDe = hiveStorageFormat.getSerDe();

            InputFormat<?, ?> inputFormat = HiveUtil.getInputFormat(configuration, inputFormatName, serDe, symlinkTarget);

            assertNotNull(inputFormat, "InputFormat should not be null for valid SerDe: " + serDe);
            assertEquals(inputFormat.getClass().getName(), hiveStorageFormat.getInputFormat());
        }
    }

    private static void assertToPartitionValues(String partitionName)
            throws MetaException
    {
        List<String> actual = toPartitionValues(partitionName);
        AbstractList<String> expected = new ArrayList<>();
        for (String s : actual) {
            expected.add(null);
        }
        Warehouse.makeValsFromName(partitionName, expected);
        assertEquals(actual, expected);
    }

    private static void assertToPartitionNamesAndValues(String partitionName, List<String> expectedKeyList)
            throws MetaException
    {
        Map<String, String> actual = toPartitionNamesAndValues(partitionName);
        AbstractList<String> expectedValueList = new ArrayList<>();
        for (String s : expectedKeyList) {
            expectedValueList.add(null);
        }
        Warehouse.makeValsFromName(partitionName, expectedValueList);
        checkState(actual.keySet().size() == expectedKeyList.size(), "Keyset size is not same");

        for (int index = 0; index < expectedKeyList.size(); index++) {
            String key = expectedKeyList.get(index);
            if (!actual.containsKey(key)) {
                break;
            }
            checkState(actual.containsKey(key), "Actual result does not contains the key");
            String actualValue = actual.get(key);
            String expectedValue = expectedValueList.get(index);
            checkState(actualValue.equals(expectedValue), "The actual value does not match the expected value");
        }
    }

    private static long parse(DateTime time, String pattern)
    {
        return parseHiveTimestamp(DateTimeFormat.forPattern(pattern).print(time), nonDefaultTimeZone());
    }

    private static long unixTime(DateTime time, int factionalDigits)
    {
        int factor = (int) Math.pow(10, Math.max(0, 3 - factionalDigits));
        return (time.getMillis() / factor) * factor;
    }

    static DateTimeZone nonDefaultTimeZone()
    {
        String defaultId = DateTimeZone.getDefault().getID();
        for (String id : DateTimeZone.getAvailableIDs()) {
            if (!id.equals(defaultId)) {
                DateTimeZone zone = DateTimeZone.forID(id);
                if (zone.getStandardOffset(0) != 0) {
                    return zone;
                }
            }
        }
        throw new IllegalStateException("no non-default timezone");
    }
}
