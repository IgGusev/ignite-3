/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.client.table;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.streamer.StreamerPartitionAwarenessProvider;

/**
 * Partition awareness provider for data streamer.
 *
 * @param <T> Item type.
 */
abstract class AbstractClientStreamerPartitionAwarenessProvider<T> implements StreamerPartitionAwarenessProvider<T, Integer> {
    private final ClientTable tbl;
    private volatile int partitions = -1;
    private volatile ClientSchema schema;

    AbstractClientStreamerPartitionAwarenessProvider(ClientTable tbl) {
        this.tbl = tbl;
    }

    @Override
    public Integer partition(T item) {
        ClientSchema schema0 = schema;
        int partitions0 = partitions;

        if (schema0 == null || partitions0 < 0) {
            throw new IllegalStateException("StreamerPartitionAwarenessProvider.refreshAsync() was not called or awaited.");
        }

        if (partitions0 == 0) {
            throw new IllegalStateException("StreamerPartitionAwarenessProvider has zero partitions.");
        }

        int hash = colocationHash(schema0, item);
        return Math.abs(hash % partitions0);
    }

    abstract int colocationHash(ClientSchema schema, T item);

    @Override
    public CompletableFuture<Void> refreshAsync() {
        var schemaFut = tbl.getLatestSchema().thenAccept(schema -> this.schema = schema);

        if (partitions < 0) {
            partitions = tbl.tryGetPartitionCount();
        }

        if (partitions > 0) {
            // Partition count can't change.
            return schemaFut;
        }

        var assignmentFut = tbl.getPartitionAssignment().thenAccept(assignment -> this.partitions = assignment.size());

        return CompletableFuture.allOf(schemaFut, assignmentFut);
    }
}
