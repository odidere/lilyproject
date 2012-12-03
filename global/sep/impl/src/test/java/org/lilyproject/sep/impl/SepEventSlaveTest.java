/*
 * Copyright 2012 NGDATA nv
 *
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
package org.lilyproject.sep.impl;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;

import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lilyproject.util.hbase.LilyHBaseSchema.RecordCf;
import org.lilyproject.util.hbase.LilyHBaseSchema.RecordColumn;
import org.lilyproject.util.hbase.LilyHBaseSchema.Table;
import org.lilyproject.util.zookeeper.ZooKeeperItf;
import org.mockito.Mockito;

public class SepEventSlaveTest {

    private EventListener eventListener;
    private ZooKeeperItf zkItf;
    private SepEventSlave eventSlave;

    @Before
    public void setUp() throws IOException, InterruptedException, KeeperException {
        eventListener = mock(EventListener.class);
        zkItf = mock(ZooKeeperItf.class);
        eventSlave = new SepEventSlave("subscriptionId", eventListener, 1, "localhost", zkItf,
                HBaseConfiguration.create());
        eventSlave.start();
    }

    @After
    public void tearDown() {
        eventSlave.stop();
    }

    private HLog.Entry[] createHlogEntries(byte[] tableName, byte[] rowKey, byte[] columnFamily, byte[] qualifier,
            byte[] data) {
        KeyValue keyValue = new KeyValue(rowKey, columnFamily, qualifier, data);
        HLog.Entry entry = mock(HLog.Entry.class, Mockito.RETURNS_DEEP_STUBS);
        when(entry.getEdit().getKeyValues()).thenReturn(Lists.newArrayList(keyValue));
        when(entry.getKey().getTablename()).thenReturn(tableName);
        return new HLog.Entry[] { entry };
    }

    @Test
    public void testReplicateLogEntries() throws IOException {

        byte[] rowKey = Bytes.toBytes("rowkey");
        byte[] payloadData = Bytes.toBytes("payload");

        HLog.Entry[] hlogEntries = createHlogEntries(Table.RECORD.bytes, rowKey, RecordCf.DATA.bytes,
                RecordColumn.PAYLOAD.bytes, payloadData);

        eventSlave.replicateLogEntries(hlogEntries);

        when(eventListener.processMessage(aryEq(rowKey), aryEq(payloadData))).thenReturn(true);

        verify(eventListener).processMessage(aryEq(rowKey), aryEq(payloadData));
    }

    @Test
    public void testReplicateLogEntries_NotForRecordTable() throws IOException {

        byte[] rowKey = Bytes.toBytes("rowkey");
        byte[] payloadData = Bytes.toBytes("payload");

        HLog.Entry[] hlogEntries = createHlogEntries(Bytes.toBytes("NotRecordTable"), rowKey, RecordCf.DATA.bytes,
                RecordColumn.PAYLOAD.bytes, payloadData);

        eventSlave.replicateLogEntries(hlogEntries);

        // Event listener shouldn't be touched as the event wasn't on the record table
        verifyNoMoreInteractions(eventListener);
    }
}