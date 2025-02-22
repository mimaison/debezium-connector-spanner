/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.spanner.db.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.common.collect.Sets;

import io.debezium.connector.spanner.db.dao.ChangeStreamResultSet;
import io.debezium.connector.spanner.db.dao.ChangeStreamResultSetMetadata;
import io.debezium.connector.spanner.db.mapper.parser.ColumnTypeParser;
import io.debezium.connector.spanner.db.model.ChildPartition;
import io.debezium.connector.spanner.db.model.Mod;
import io.debezium.connector.spanner.db.model.ModType;
import io.debezium.connector.spanner.db.model.Partition;
import io.debezium.connector.spanner.db.model.StreamEventMetadata;
import io.debezium.connector.spanner.db.model.ValueCaptureType;
import io.debezium.connector.spanner.db.model.event.ChildPartitionsEvent;
import io.debezium.connector.spanner.db.model.event.DataChangeEvent;
import io.debezium.connector.spanner.db.model.event.HeartbeatEvent;
import io.debezium.connector.spanner.db.model.schema.Column;

class ChangeStreamRecordMapperTest {
    ChangeStreamResultSetMetadata resultSetMetadata;
    Partition partition;
    ChangeStreamRecordMapper changeStreamRecordMapper;
    DatabaseClient psqlDatabaseClient = mock(DatabaseClient.class);
    DatabaseClient gsqlDatabaseClient = mock(DatabaseClient.class);

    @BeforeEach
    public void setUp() {
        when(psqlDatabaseClient.getDialect()).thenReturn(Dialect.POSTGRESQL);
        resultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(resultSetMetadata.getQueryStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(resultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(2L));
        when(resultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(3L));
        when(resultSetMetadata.getRecordReadAt()).thenReturn(Timestamp.ofTimeMicroseconds(4L));
        when(resultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(100));
        when(resultSetMetadata.getNumberOfRecordsRead()).thenReturn(10_000L);
        partition = new Partition("partitionToken", Sets.newHashSet("parentToken"), Timestamp.ofTimeMicroseconds(11L),
                Timestamp.ofTimeMicroseconds(12L), "parentToken");
        changeStreamRecordMapper = new ChangeStreamRecordMapper(
                psqlDatabaseClient);
    }

    @Test
    public void testMappingUpdateJsonRowToDataChangeRecord() {

        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "serverTransactionId",
                true,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(
                        new Mod(1,
                                MapperUtils.getJsonNode("{\"column1\":\"value1\"}"),
                                MapperUtils.getJsonNode("{\"column2\":\"oldValue2\"}"),
                                MapperUtils.getJsonNode("{\"column2\":\"newValue2\"}"))),
                ModType.UPDATE,
                ValueCaptureType.OLD_AND_NEW_VALUES,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));

        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    /*
     * Change streams with NEW_ROW value capture type do not track old values, so null value
     * is used for OLD_VALUES_COLUMN in Mod.
     */
    @Test
    public void testMappingUpdateJsonRowNewRowToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "serverTransactionId",
                true,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(
                        new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{}"),
                                MapperUtils.getJsonNode("{\"column2\":\"newValue2\"}"))),
                ModType.UPDATE,
                ValueCaptureType.NEW_ROW,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingInsertJsonRowNewValuesToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "transactionId",
                false,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(
                        new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{}"),
                                MapperUtils.getJsonNode("{\"column2\":\"newValue2\"}"))),
                ModType.INSERT,
                ValueCaptureType.NEW_VALUES,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingDeleteJsonRowToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "transactionId",
                false,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(
                        new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{\"column2\":\"oldValue2\"}"),
                                MapperUtils.getJsonNode("{}"))),
                ModType.DELETE,
                ValueCaptureType.OLD_AND_NEW_VALUES,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingDeleteJsonRowNewRowToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "transactionId",
                false,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{}"), MapperUtils.getJsonNode("{}"))),
                ModType.DELETE,
                ValueCaptureType.NEW_ROW,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingDeleteJsonRowNewValuesToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "transactionId",
                false,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{}"), MapperUtils.getJsonNode("{}"))),
                ModType.DELETE,
                ValueCaptureType.NEW_VALUES,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingJsonRowWithUnknownModTypeAndValueCaptureTypeToDataChangeRecord() {
        final DataChangeEvent dataChangeRecord = new DataChangeEvent(
                "partitionToken",
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "transactionId",
                false,
                "1",
                "tableName",
                Arrays.asList(
                        new Column("column1", ColumnTypeParser.parse("{\"code\":\"INT64\"}"), true, 1L, false),
                        new Column("column2", ColumnTypeParser.parse("{\"code\":\"BYTES\"}"), false, 2L, true)),
                Collections.singletonList(
                        new Mod(1, MapperUtils.getJsonNode("{\"column1\":\"value1\"}"), MapperUtils.getJsonNode("{}"),
                                MapperUtils.getJsonNode("{\"column2\":\"newValue2\"}"))),
                ModType.UNKNOWN,
                ValueCaptureType.UNKNOWN,
                10L,
                2L,
                "transactionTag",
                true,
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(dataChangeRecord, true, true);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(dataChangeRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingJsonRowToHeartbeatRecord() {
        final HeartbeatEvent heartbeatRecord = new HeartbeatEvent(Timestamp.ofTimeSecondsAndNanos(10L, 20),
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(heartbeatRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(heartbeatRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    public void testMappingJsonRowToChildPartitionRecord() {
        final ChildPartitionsEvent childPartitionsRecord = new ChildPartitionsEvent(
                Timestamp.ofTimeSecondsAndNanos(10L, 20),
                "1",
                Arrays.asList(
                        new ChildPartition("childToken1", Sets.newHashSet("parentToken1", "parentToken2")),
                        new ChildPartition("childToken2", Sets.newHashSet("parentToken1", "parentToken2"))),
                changeStreamRecordMapper.streamEventMetadataFrom(partition, Timestamp.ofTimeSecondsAndNanos(10L, 20), resultSetMetadata));
        final String jsonString = TestJsonMapper.recordToJson(childPartitionsRecord, false, false);

        assertNotNull(jsonString);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getPgJsonb(0)).thenReturn(jsonString);
        assertEquals(
                Collections.singletonList(childPartitionsRecord),
                changeStreamRecordMapper.toChangeStreamEvents(partition, resultSet, resultSetMetadata));
    }

    @Test
    void testToChangeStreamEvents() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(
                gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("token", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(anyInt())).thenReturn(new ArrayList<>());

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct);

        assertTrue(changeStreamRecordMapper
                .toChangeStreamEvents(partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());
        verify(struct).getStructList(anyInt());
    }

    @Test
    void testToChangeStreamEvents2() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("token", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct);
        Struct row = mock(Struct.class);
        when(row.getStructList(anyInt())).thenReturn(structList);

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(row);

        assertTrue(changeStreamRecordMapper
                .toChangeStreamEvents(partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());

        verify(row).getStructList(anyInt());
        verify(struct, atLeast(1)).getStructList(any());
    }

    @Test
    void testToChangeStreamEvents3() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("token", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct1 = mock(Struct.class);
        when(struct1.getStructList(any())).thenReturn(new ArrayList<>());
        Struct struct2 = mock(Struct.class);
        when(struct2.getStructList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct1);
        structList.add(struct2);

        Struct row = mock(Struct.class);
        when(row.getStructList(anyInt())).thenReturn(structList);

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(row);

        assertTrue(changeStreamRecordMapper
                .toChangeStreamEvents(partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());

        verify(row).getStructList(anyInt());
        verify(struct1, atLeast(1)).getStructList(any());
        verify(struct2, atLeast(1)).getStructList(any());
    }

    @Test
    void testToChangeStreamEvents4() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("token", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct1 = mock(Struct.class);
        when(struct1.getStructList(any())).thenReturn(new ArrayList<>());
        Struct struct2 = mock(Struct.class);
        when(struct2.getStructList(any())).thenThrow(new IllegalArgumentException());
        Struct struct3 = mock(Struct.class);
        when(struct3.getStructList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct1);
        structList.add(struct2);
        structList.add(struct3);

        Struct row = mock(Struct.class);
        when(row.getStructList(anyInt())).thenReturn(structList);

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(row);

        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper
                .toChangeStreamEvents(partition, resultSet, mock(ChangeStreamResultSetMetadata.class)));

        verify(row).getStructList(anyInt());
        verify(struct2).getStructList(any());
    }

    @Test
    void testToChangeStreamEvents5() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(anyInt())).thenReturn(new ArrayList<>());

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct);
        assertTrue(changeStreamRecordMapper.toChangeStreamEvents(
                partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());
        verify(struct).getStructList(anyInt());
    }

    @Test
    void testToChangeStreamEvents6() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct);
        Struct struct1 = mock(Struct.class);
        when(struct1.getStructList(anyInt())).thenReturn(structList);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct1);
        assertTrue(changeStreamRecordMapper.toChangeStreamEvents(
                partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());
        verify(struct1).getStructList(anyInt());
        verify(struct, atLeast(1)).getStructList(any());
    }

    @Test
    void testToChangeStreamEvents7() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());
        Struct struct1 = mock(Struct.class);
        when(struct1.getStructList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct1);
        structList.add(struct);
        Struct struct2 = mock(Struct.class);
        when(struct2.getStructList(anyInt())).thenReturn(structList);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct2);
        assertTrue(changeStreamRecordMapper.toChangeStreamEvents(
                partition, resultSet, mock(ChangeStreamResultSetMetadata.class)).isEmpty());
        verify(struct2).getStructList(anyInt());
        verify(struct1, atLeast(1)).getStructList(any());
        verify(struct, atLeast(1)).getStructList(any());
    }

    @Test
    void testToChangeStreamEvents8() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());
        Struct struct1 = mock(Struct.class);
        when(struct1.getStructList(any())).thenReturn(new ArrayList<>());
        Struct struct2 = mock(Struct.class);
        when(struct2.getStructList(any())).thenThrow(new IllegalArgumentException());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct2);
        structList.add(struct1);
        structList.add(struct);
        Struct struct3 = mock(Struct.class);
        when(struct3.getStructList(anyInt())).thenReturn(structList);
        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct3);
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.toChangeStreamEvents(partition,
                resultSet, mock(ChangeStreamResultSetMetadata.class)));
        verify(struct3).getStructList(anyInt());
        verify(struct2).getStructList(any());
    }

    @Test
    void testToStreamEvent() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct);

        changeStreamRecordMapper.toStreamEvent(partition, struct, mock(ChangeStreamResultSetMetadata.class));
        verify(struct, atLeast(1)).getStructList(any());
    }

    @Test
    void testToStreamEventThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getStructList(any())).thenThrow(new IllegalArgumentException());

        ChangeStreamResultSet resultSet = mock(ChangeStreamResultSet.class);
        when(resultSet.getCurrentRowAsStruct()).thenReturn(struct);

        assertThrows(IllegalArgumentException.class,
                () -> changeStreamRecordMapper.toStreamEvent(partition, struct, mock(ChangeStreamResultSetMetadata.class)));
        verify(struct).getStructList(any());
    }

    @Test
    void testIsNonNullDataChangeRecordNullDataChangeRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(true);
        assertFalse(changeStreamRecordMapper.isNonNullDataChangeRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullDataChangeRecordNonNullDataChangeRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(false);
        assertTrue(changeStreamRecordMapper.isNonNullDataChangeRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullDataChangeRecordThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.isNonNullDataChangeRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullHeartbeatRecordNullHeartbeatRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(true);
        assertFalse(changeStreamRecordMapper.isNonNullHeartbeatRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullHeartbeatRecordNonNullHeartbeatRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(false);
        assertTrue(changeStreamRecordMapper.isNonNullHeartbeatRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullHeartbeatRecordThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.isNonNullHeartbeatRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullChildPartitionsRecordNullChildPartitionsRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(true);
        assertFalse(changeStreamRecordMapper.isNonNullChildPartitionsRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullChildPartitionsRecordNonNullChildPartitionsRecord() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenReturn(false);
        assertTrue(changeStreamRecordMapper.isNonNullChildPartitionsRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testIsNonNullChildPartitionsRecordThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.isNull(any())).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class,
                () -> changeStreamRecordMapper.isNonNullChildPartitionsRecord(struct));
        verify(struct).isNull(any());
    }

    @Test
    void testToDataChangeEvent() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getBoolean(any())).thenReturn(true);
        when(struct.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct.getString(any())).thenReturn("String");
        when(struct.getString("mod_type")).thenReturn("INSERT");
        when(struct.getString("value_capture_type")).thenReturn("NEW_ROW");
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());

        ChangeStreamResultSetMetadata metadata = mock(ChangeStreamResultSetMetadata.class);
        when(metadata.getTotalStreamDuration()).thenReturn(Duration.millis(1));

        DataChangeEvent dataChangeEvent = changeStreamRecordMapper.toDataChangeEvent(
                partition, struct, metadata);
        assertEquals("String", dataChangeEvent.getServerTransactionId());
        assertEquals(startTimestamp, dataChangeEvent.getCommitTimestamp());
    }

    @Test
    void testToDataChangeEventThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.bool());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct);
        Struct struct1 = mock(Struct.class);
        when(struct1.getBoolean(any())).thenReturn(true);
        when(struct1.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct1.getString(any())).thenReturn("String");
        when(struct1.getStructList(any())).thenReturn(structList);
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.toDataChangeEvent(partition, struct1,
                mock(ChangeStreamResultSetMetadata.class)));
        verify(struct1).getBoolean(any());
        verify(struct1).getTimestamp(any());
        verify(struct1, atLeast(1)).getString(any());
        verify(struct1).getStructList(any());
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testToHeartbeatEvent() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        Timestamp ofTimeMicrosecondsResult1 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(ofTimeMicrosecondsResult1);
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        HeartbeatEvent actualToHeartbeatEventResult = changeStreamRecordMapper.toHeartbeatEvent(partition, struct,
                changeStreamResultSetMetadata);
        Timestamp timestamp = actualToHeartbeatEventResult.getTimestamp();
        assertEquals(ofTimeMicrosecondsResult, timestamp);
        StreamEventMetadata metadata = actualToHeartbeatEventResult.getMetadata();
        assertSame(timestamp, metadata.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamStartedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamEndedAt());
        assertEquals(1L, metadata.getTotalStreamTimeMillis());
        assertEquals(ofTimeMicrosecondsResult, metadata.getPartitionEndTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordReadAt());
        assertEquals(ofTimeMicrosecondsResult1, metadata.getPartitionStartTimestamp());
        assertEquals(1L, metadata.getNumberOfRecordsRead());
        assertEquals("String", metadata.getPartitionToken());
        assertEquals(timestamp, metadata.getQueryStartedAt());
        verify(struct).getTimestamp(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testToHeartbeatEvent2() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenThrow(new IllegalArgumentException());
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        assertThrows(IllegalArgumentException.class,
                () -> changeStreamRecordMapper.toHeartbeatEvent(partition, struct, changeStreamResultSetMetadata));
        verify(struct).getTimestamp(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testToChildPartitionsEvent() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct.getString(any())).thenReturn("String");
        ArrayList<Struct> structList = new ArrayList<>();
        when(struct.getStructList(any())).thenReturn(structList);
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        ChildPartitionsEvent actualToChildPartitionsEventResult = changeStreamRecordMapper
                .toChildPartitionsEvent(partition, struct, changeStreamResultSetMetadata);
        assertEquals(structList, actualToChildPartitionsEventResult.getChildPartitions());
        Timestamp startTimestamp = actualToChildPartitionsEventResult.getStartTimestamp();
        assertEquals(ofTimeMicrosecondsResult, startTimestamp);
        assertEquals("String", actualToChildPartitionsEventResult.getRecordSequence());
        StreamEventMetadata metadata = actualToChildPartitionsEventResult.getMetadata();
        assertEquals(1L, metadata.getTotalStreamTimeMillis());
        assertSame(startTimestamp, metadata.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamStartedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamEndedAt());
        assertEquals("String", metadata.getPartitionToken());
        assertEquals(ofTimeMicrosecondsResult, metadata.getQueryStartedAt());
        assertEquals(1L, metadata.getNumberOfRecordsRead());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordReadAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getPartitionEndTimestamp());
        assertEquals(startTimestamp, metadata.getPartitionStartTimestamp());
        verify(struct).getTimestamp(any());
        verify(struct).getString(any());
        verify(struct).getStructList(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testToChildPartitionsEventThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp startTimestamp = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, startTimestamp, Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStructList(any())).thenReturn(new ArrayList<>());
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenThrow(new IllegalArgumentException());
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        assertThrows(IllegalArgumentException.class,
                () -> changeStreamRecordMapper.toChildPartitionsEvent(partition, struct, changeStreamResultSetMetadata));
        verify(struct).getTimestamp(any());
        verify(struct).getString(any());
        verify(struct).getStructList(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testToChildPartitionsEvent4() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStringList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct);
        Struct struct1 = mock(Struct.class);
        when(struct1.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct1.getString(any())).thenReturn("String");
        when(struct1.getStructList(any())).thenReturn(structList);
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        Timestamp ofTimeMicrosecondsResult1 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(ofTimeMicrosecondsResult1);
        Timestamp ofTimeMicrosecondsResult2 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(ofTimeMicrosecondsResult2);
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        ChildPartitionsEvent actualToChildPartitionsEventResult = changeStreamRecordMapper
                .toChildPartitionsEvent(partition, struct1, changeStreamResultSetMetadata);
        List<ChildPartition> childPartitions = actualToChildPartitionsEventResult.getChildPartitions();
        assertEquals(1, childPartitions.size());
        Timestamp startTimestamp = actualToChildPartitionsEventResult.getStartTimestamp();
        assertEquals(ofTimeMicrosecondsResult, startTimestamp);
        assertEquals("String", actualToChildPartitionsEventResult.getRecordSequence());
        StreamEventMetadata metadata = actualToChildPartitionsEventResult.getMetadata();
        assertSame(startTimestamp, metadata.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamStartedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamEndedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getPartitionEndTimestamp());
        assertEquals(ofTimeMicrosecondsResult1, metadata.getPartitionStartTimestamp());
        assertEquals("String", metadata.getPartitionToken());
        assertEquals(ofTimeMicrosecondsResult2, metadata.getQueryStartedAt());
        assertEquals(1L, metadata.getTotalStreamTimeMillis());
        assertEquals(startTimestamp, metadata.getRecordReadAt());
        assertEquals(1L, metadata.getNumberOfRecordsRead());
        ChildPartition getResult = childPartitions.get(0);
        assertTrue(getResult.getParentTokens().isEmpty());
        assertEquals("String", getResult.getToken());
        verify(struct1).getTimestamp(any());
        verify(struct1).getString(any());
        verify(struct1).getStructList(any());
        verify(struct).getString(any());
        verify(struct).getStringList(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    /**
     * Method under test: {@link ChangeStreamRecordMapper#toChildPartitionsEvent(Partition, Struct, ChangeStreamResultSetMetadata)}
     */
    @Test
    void testToChildPartitionsEvent5() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("Parent0", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStringList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct);
        Struct struct1 = mock(Struct.class);
        when(struct1.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct1.getString(any())).thenReturn("String");
        when(struct1.getStructList(any())).thenReturn(structList);
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        Timestamp ofTimeMicrosecondsResult1 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(ofTimeMicrosecondsResult1);
        Timestamp ofTimeMicrosecondsResult2 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(ofTimeMicrosecondsResult2);
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        ChildPartitionsEvent actualToChildPartitionsEventResult = changeStreamRecordMapper
                .toChildPartitionsEvent(partition, struct1, changeStreamResultSetMetadata);
        List<ChildPartition> childPartitions = actualToChildPartitionsEventResult.getChildPartitions();
        assertEquals(1, childPartitions.size());
        Timestamp startTimestamp = actualToChildPartitionsEventResult.getStartTimestamp();
        assertEquals(ofTimeMicrosecondsResult, startTimestamp);
        assertEquals("String", actualToChildPartitionsEventResult.getRecordSequence());
        StreamEventMetadata metadata = actualToChildPartitionsEventResult.getMetadata();
        assertSame(startTimestamp, metadata.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamStartedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamEndedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getPartitionEndTimestamp());
        assertEquals(ofTimeMicrosecondsResult1, metadata.getPartitionStartTimestamp());
        assertEquals("Parent0", metadata.getPartitionToken());
        assertEquals(ofTimeMicrosecondsResult2, metadata.getQueryStartedAt());
        assertEquals(1L, metadata.getTotalStreamTimeMillis());
        assertEquals(startTimestamp, metadata.getRecordReadAt());
        assertEquals(1L, metadata.getNumberOfRecordsRead());
        ChildPartition getResult = childPartitions.get(0);
        assertEquals(1, getResult.getParentTokens().size());
        assertEquals("String", getResult.getToken());
        verify(struct1).getTimestamp(any());
        verify(struct1).getString(any());
        verify(struct1).getStructList(any());
        verify(struct).getString(any());
        verify(struct).getStringList(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testToChildPartitionsEvent7() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStringList(any())).thenReturn(new ArrayList<>());
        Struct struct1 = mock(Struct.class);
        when(struct1.getString(any())).thenReturn("String");
        when(struct1.getStringList(any())).thenReturn(new ArrayList<>());

        ArrayList<Struct> structList = new ArrayList<>();
        structList.add(struct1);
        structList.add(struct);
        Struct struct2 = mock(Struct.class);
        when(struct2.getTimestamp(any())).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(struct2.getString(any())).thenReturn("String");
        when(struct2.getStructList(any())).thenReturn(structList);
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        Timestamp ofTimeMicrosecondsResult1 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(ofTimeMicrosecondsResult1);
        Timestamp ofTimeMicrosecondsResult2 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(ofTimeMicrosecondsResult2);
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        ChildPartitionsEvent actualToChildPartitionsEventResult = changeStreamRecordMapper
                .toChildPartitionsEvent(partition, struct2, changeStreamResultSetMetadata);
        List<ChildPartition> childPartitions = actualToChildPartitionsEventResult.getChildPartitions();
        assertEquals(2, childPartitions.size());
        Timestamp startTimestamp = actualToChildPartitionsEventResult.getStartTimestamp();
        assertEquals(ofTimeMicrosecondsResult, startTimestamp);
        assertEquals("String", actualToChildPartitionsEventResult.getRecordSequence());
        StreamEventMetadata metadata = actualToChildPartitionsEventResult.getMetadata();
        assertSame(startTimestamp, metadata.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamStartedAt());
        assertEquals(ofTimeMicrosecondsResult, metadata.getRecordStreamEndedAt());
        assertEquals(1L, metadata.getNumberOfRecordsRead());
        assertEquals(ofTimeMicrosecondsResult, metadata.getPartitionEndTimestamp());
        assertEquals(ofTimeMicrosecondsResult1, metadata.getPartitionStartTimestamp());
        assertEquals("String", metadata.getPartitionToken());
        assertEquals(1L, metadata.getTotalStreamTimeMillis());
        assertEquals(ofTimeMicrosecondsResult2, metadata.getQueryStartedAt());
        assertEquals(startTimestamp, metadata.getRecordReadAt());
        ChildPartition getResult = childPartitions.get(1);
        Set<String> parentTokens1 = getResult.getParentTokens();
        assertTrue(parentTokens1.isEmpty());
        assertEquals("String", getResult.getToken());
        ChildPartition getResult1 = childPartitions.get(0);
        assertEquals(parentTokens1, getResult1.getParentTokens());
        assertEquals("String", getResult1.getToken());
        verify(struct2).getTimestamp(any());
        verify(struct2).getString(any());
        verify(struct2).getStructList(any());
        verify(struct1).getString(any());
        verify(struct1).getStringList(any());
        verify(struct).getString(any());
        verify(struct).getStringList(any());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testColumnTypeFrom() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.string());
        when(struct.getString(any())).thenReturn("{\"code\":\"STRING\"}");
        Column column = changeStreamRecordMapper.columnTypeFrom(struct);
        assertEquals("{\"code\":\"STRING\"}", column.getName());
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testColumnTypeFromThrowsBool() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.bool());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.columnTypeFrom(struct));
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testColumnTypeFromThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.columnTypeFrom(struct));
        verify(struct).getColumnType(any());
    }

    @Test
    void testModFrom() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.bool());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.modFrom(0, struct));
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testModFromString() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.string());
        String jsonString = "{\"code\":\"STRING\"}";
        when(struct.getString(any())).thenReturn(jsonString);
        JsonNode jsonNode = MapperUtils.getJsonNode(jsonString);
        Mod expected = new Mod(0, jsonNode, jsonNode, jsonNode);
        Mod mod = changeStreamRecordMapper.modFrom(0, struct);
        assertEquals(expected.keysJsonNode(), mod.keysJsonNode());
        assertEquals(expected.oldValuesJsonNode(), mod.oldValuesJsonNode());
        assertEquals(expected.newValuesJsonNode(), mod.newValuesJsonNode());
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testEmptyChildPartitionFrom() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStringList(any())).thenReturn(new ArrayList<>());
        ChildPartition actualChildPartitionFromResult = changeStreamRecordMapper.childPartitionFrom("String", struct);
        assertTrue(actualChildPartitionFromResult.getParentTokens().isEmpty());
        assertEquals("String", actualChildPartitionFromResult.getToken());
        verify(struct).getString(any());
        verify(struct).getStringList(any());
    }

    @Test
    void testChildPartitionFrom() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenReturn("String");
        when(struct.getStringList(any())).thenReturn(new ArrayList<>());
        ChildPartition actualChildPartitionFromResult = changeStreamRecordMapper.childPartitionFrom("Parent0", struct);
        assertEquals(1, actualChildPartitionFromResult.getParentTokens().size());
        assertEquals("String", actualChildPartitionFromResult.getToken());
        verify(struct).getString(any());
        verify(struct).getStringList(any());
    }

    @Test
    void testChildPartitionFromThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getString(any())).thenThrow(new IllegalArgumentException());
        when(struct.getStringList(any())).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.childPartitionFrom("Parent0", struct));
        verify(struct).getStringList(any());
    }

    @Test
    void testStreamEventMetadataFrom() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        HashSet<String> parentTokens = new HashSet<>();
        Timestamp ofTimeMicrosecondsResult = Timestamp.ofTimeMicroseconds(1L);
        Partition partition = new Partition("String", parentTokens, ofTimeMicrosecondsResult,
                Timestamp.ofTimeMicroseconds(1L), "originPartition");

        Timestamp recordTimestamp = Timestamp.ofTimeMicroseconds(1L);
        ChangeStreamResultSetMetadata changeStreamResultSetMetadata = mock(ChangeStreamResultSetMetadata.class);
        when(changeStreamResultSetMetadata.getNumberOfRecordsRead()).thenReturn(1L);
        when(changeStreamResultSetMetadata.getQueryStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        Timestamp ofTimeMicrosecondsResult1 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getRecordReadAt()).thenReturn(ofTimeMicrosecondsResult1);
        Timestamp ofTimeMicrosecondsResult2 = Timestamp.ofTimeMicroseconds(1L);
        when(changeStreamResultSetMetadata.getRecordStreamEndedAt()).thenReturn(ofTimeMicrosecondsResult2);
        when(changeStreamResultSetMetadata.getRecordStreamStartedAt()).thenReturn(Timestamp.ofTimeMicroseconds(1L));
        when(changeStreamResultSetMetadata.getTotalStreamDuration()).thenReturn(Duration.millis(1L));
        StreamEventMetadata actualStreamEventMetadataFromResult = changeStreamRecordMapper
                .streamEventMetadataFrom(partition, recordTimestamp, changeStreamResultSetMetadata);
        assertEquals(1L, actualStreamEventMetadataFromResult.getNumberOfRecordsRead());
        assertEquals(1L, actualStreamEventMetadataFromResult.getTotalStreamTimeMillis());
        assertEquals(ofTimeMicrosecondsResult, actualStreamEventMetadataFromResult.getRecordTimestamp());
        assertEquals(ofTimeMicrosecondsResult, actualStreamEventMetadataFromResult.getRecordStreamStartedAt());
        Timestamp partitionEndTimestamp = actualStreamEventMetadataFromResult.getPartitionEndTimestamp();
        assertEquals(ofTimeMicrosecondsResult, partitionEndTimestamp);
        assertEquals("String", actualStreamEventMetadataFromResult.getPartitionToken());
        assertEquals(ofTimeMicrosecondsResult, actualStreamEventMetadataFromResult.getQueryStartedAt());
        assertEquals(ofTimeMicrosecondsResult1, actualStreamEventMetadataFromResult.getPartitionStartTimestamp());
        assertEquals(ofTimeMicrosecondsResult2, actualStreamEventMetadataFromResult.getRecordReadAt());
        assertEquals(partitionEndTimestamp, actualStreamEventMetadataFromResult.getRecordStreamEndedAt());
        verify(changeStreamResultSetMetadata).getQueryStartedAt();
        verify(changeStreamResultSetMetadata).getRecordReadAt();
        verify(changeStreamResultSetMetadata).getRecordStreamEndedAt();
        verify(changeStreamResultSetMetadata).getRecordStreamStartedAt();
        verify(changeStreamResultSetMetadata).getNumberOfRecordsRead();
        verify(changeStreamResultSetMetadata).getTotalStreamDuration();
    }

    @Test
    void testGetJsonStringThrows() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getColumnType(any())).thenReturn(Type.bool());
        assertThrows(IllegalArgumentException.class, () -> changeStreamRecordMapper.getJsonString(struct, "Column Name"));
        verify(struct, atLeast(1)).getColumnType(any());
    }

    @Test
    void testGetJsonString() {
        when(gsqlDatabaseClient.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
        ChangeStreamRecordMapper changeStreamRecordMapper = new ChangeStreamRecordMapper(gsqlDatabaseClient);
        Struct struct = mock(Struct.class);
        when(struct.getJson(any())).thenReturn("Json");
        when(struct.getColumnType(any())).thenReturn(Type.json());
        assertEquals("Json", changeStreamRecordMapper.getJsonString(struct, "Column Name"));
        verify(struct).getColumnType(any());
        verify(struct).getJson(any());
    }

}
