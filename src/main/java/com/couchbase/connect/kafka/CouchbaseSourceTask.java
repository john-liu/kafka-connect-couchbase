/**
 * Copyright 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connect.kafka;

import com.couchbase.client.dcp.message.DcpDeletionMessage;
import com.couchbase.client.dcp.message.DcpExpirationMessage;
import com.couchbase.client.dcp.message.DcpMutationMessage;
import com.couchbase.client.dcp.message.MessageUtil;
import com.couchbase.client.dcp.state.PartitionState;
import com.couchbase.client.dcp.state.SessionState;
import com.couchbase.client.dcp.state.StateFormat;
import com.couchbase.client.deps.io.netty.buffer.ByteBuf;
import com.couchbase.client.deps.io.netty.util.CharsetUtil;
import com.couchbase.connect.kafka.dcp.EventType;
import com.couchbase.connect.kafka.util.Schemas;
import com.couchbase.connect.kafka.util.Version;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class CouchbaseSourceTask extends SourceTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseSourceConnector.class);

    private static final long MAX_TIMEOUT = 10000L;

    private CouchbaseSourceConnectorConfig config;
    private Map<String, String> configProperties;
    private CouchbaseMonitorThread couchbaseMonitorThread;
    private BlockingQueue<ByteBuf> queue;
    private String topic;
    private String bucket;
    private volatile boolean running;

    @Override
    public String version() {
        return Version.getVersion();
    }

    @Override
    public void start(Map<String, String> properties) {
        try {
            configProperties = properties;
            config = new CouchbaseSourceTaskConfig(configProperties);
        } catch (ConfigException e) {
            throw new ConnectException("Couldn't start CouchbaseSourceTask due to configuration error", e);
        }

        topic = config.getString(CouchbaseSourceConnectorConfig.TOPIC_NAME_CONFIG);
        bucket = config.getString(CouchbaseSourceConnectorConfig.CONNECTION_BUCKET_CONFIG);
        String password = config.getString(CouchbaseSourceConnectorConfig.CONNECTION_PASSWORD_CONFIG);
        List<String> clusterAddress = getList(config, CouchbaseSourceConnectorConfig.CONNECTION_CLUSTER_ADDRESS_CONFIG);

        long connectionTimeout = config.getLong(CouchbaseSourceConnectorConfig.CONNECTION_TIMEOUT_MS_CONFIG);
        List<String> partitionsList = config.getList(CouchbaseSourceTaskConfig.PARTITIONS_CONFIG);

        Short[] partitions = new Short[partitionsList.size()];
        List<Map<String, String>> kafkaPartitions = new ArrayList<Map<String, String>>(1);
        for (int i = 0; i < partitionsList.size(); i++) {
            partitions[i] = Short.parseShort(partitionsList.get(i));
            Map<String, String> kafkaPartition = new HashMap<String, String>(2);
            kafkaPartition.put("bucket", bucket);
            kafkaPartition.put("partition", partitions[i].toString());
            kafkaPartitions.add(kafkaPartition);
        }
        Map<Map<String, String>, Map<String, Object>> offsets = context.offsetStorageReader().offsets(kafkaPartitions);
        SessionState sessionState = new SessionState();
        sessionState.setToBeginningWithNoEnd(1024); // FIXME: literal
        for (Map<String, String> kafkaPartition : kafkaPartitions) {
            Map<String, Object> offset = offsets.get(kafkaPartition);
            Short partition = Short.parseShort(kafkaPartition.get("partition"));
            PartitionState partitionState = sessionState.get(partition);
            long startSeqno = 0;
            if (offset != null && offset.containsKey("bySeqno")) {
                startSeqno = (Long) offset.get("bySeqno");
            }
            partitionState.setStartSeqno(startSeqno);
            partitionState.setEndSeqno(0xffffffff);
            partitionState.setSnapshotStartSeqno(startSeqno);
            partitionState.setSnapshotEndSeqno(startSeqno);
            sessionState.set(partition, partitionState);
        }

        running = true;
        queue = new LinkedBlockingQueue<ByteBuf>();
        couchbaseMonitorThread = new CouchbaseMonitorThread(clusterAddress, bucket, password, connectionTimeout, queue, partitions, sessionState);
        couchbaseMonitorThread.start();
    }

    // FIXME: remove when type handling will be fixed in Confluent Control Center
    private static List<String> getList(CouchbaseSourceConnectorConfig config, String key) {
        String stringValue = config.getString(key);
        if (stringValue.isEmpty()) {
            return Collections.emptyList();
        } else {
            return Arrays.asList(stringValue.split("\\s*,\\s*", -1));
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        List<SourceRecord> results = new ArrayList<SourceRecord>();

        while (running) {
            ByteBuf event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                SourceRecord record = convert(event);
                if (record != null) {
                    results.add(record);
                }
                couchbaseMonitorThread.acknowledgeBuffer(event);
                event.release();
            } else if (!results.isEmpty()) {
                LOGGER.info("Poll returns {} result(s)", results.size());
                return results;
            }
        }
        return results;
    }

    public SourceRecord convert(ByteBuf event) {
        EventType type = EventType.of(event);
        if (type != null) {
            Schema schema = Schemas.VALUE_SCHEMAS.get(type);
            Struct record = new Struct(schema);
            String key;
            long seqno;
            if (DcpMutationMessage.is(event)) {
                key = bufToString(DcpMutationMessage.key(event));
                seqno = DcpMutationMessage.bySeqno(event);
                record.put("partition", DcpMutationMessage.partition(event));
                record.put("key", key);
                record.put("expiration", DcpMutationMessage.expiry(event));
                record.put("flags", DcpMutationMessage.flags(event));
                record.put("cas", DcpMutationMessage.cas(event));
                record.put("lockTime", DcpMutationMessage.lockTime(event));
                record.put("bySeqno", seqno);
                record.put("revSeqno", DcpMutationMessage.revisionSeqno(event));
                record.put("content", bufToBytes(DcpMutationMessage.content(event)));
            } else if (DcpDeletionMessage.is(event)) {
                key = bufToString(DcpDeletionMessage.key(event));
                seqno = DcpDeletionMessage.bySeqno(event);
                record.put("partition", DcpDeletionMessage.partition(event));
                record.put("key", key);
                record.put("cas", DcpDeletionMessage.cas(event));
                record.put("bySeqno", seqno);
                record.put("revSeqno", DcpDeletionMessage.revisionSeqno(event));
            } else if (DcpExpirationMessage.is(event)) {
                key = bufToString(DcpExpirationMessage.key(event));
                seqno = DcpExpirationMessage.bySeqno(event);
                record.put("partition", DcpExpirationMessage.partition(event));
                record.put("key", key);
                record.put("cas", DcpExpirationMessage.cas(event));
                record.put("bySeqno", seqno);
                record.put("revSeqno", DcpExpirationMessage.revisionSeqno(event));
            } else {
                LOGGER.warn("unexpected event type {}", event.getByte(1));
                return null;
            }
            final Map<String, Object> offset = new HashMap<String, Object>(2);
            offset.put("bySeqno", seqno);
            final Map<String, String> partition = new HashMap<String, String>(2);
            partition.put("bucket", bucket);
            partition.put("partition", record.getInt16("partition").toString());

            return new SourceRecord(partition, offset, topic, Schemas.KEY_SCHEMA, key, schema, record);
        }
        return null;
    }

    @Override
    public void stop() {
        running = false;
        couchbaseMonitorThread.shutdown();
        try {
            couchbaseMonitorThread.join(MAX_TIMEOUT);
        } catch (InterruptedException e) {
            // Ignore, shouldn't be interrupted
        }
    }
    
    private static String bufToString(ByteBuf buf) {
        return new String(bufToBytes(buf), CharsetUtil.UTF_8);
    }

    private static byte[] bufToBytes(ByteBuf buf) {
        byte[] bytes;
        bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }
}
