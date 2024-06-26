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

package org.apache.ignite.internal.pagememory.persistence;

import static org.apache.ignite.internal.pagememory.PageIdAllocator.FLAG_AUX;
import static org.apache.ignite.internal.pagememory.persistence.PartitionMeta.partitionMetaPageId;
import static org.apache.ignite.internal.pagememory.util.PageIdUtils.flag;
import static org.apache.ignite.internal.pagememory.util.PageIdUtils.pageIndex;
import static org.apache.ignite.internal.pagememory.util.PageIdUtils.partitionId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.apache.ignite.internal.pagememory.persistence.PartitionMeta.PartitionMetaSnapshot;
import org.junit.jupiter.api.Test;

/**
 * For {@link PartitionMeta} testing.
 */
public class PartitionMetaTest {
    @Test
    void testLastAppliedIndex() {
        PartitionMeta meta = new PartitionMeta();

        assertEquals(0, meta.lastAppliedIndex());

        assertDoesNotThrow(() -> meta.lastApplied(null, 100, 10));

        assertEquals(100, meta.lastAppliedIndex());

        assertDoesNotThrow(() -> meta.lastApplied(UUID.randomUUID(), 500, 50));

        assertEquals(500, meta.lastAppliedIndex());
    }

    @Test
    void testLastAppliedTerm() {
        PartitionMeta meta = new PartitionMeta();

        assertEquals(0, meta.lastAppliedTerm());

        meta.lastApplied(null, 100, 10);

        assertEquals(10, meta.lastAppliedTerm());

        meta.lastApplied(UUID.randomUUID(), 500, 50);

        assertEquals(50, meta.lastAppliedTerm());
    }

    @Test
    void testLastGroupConfig() {
        PartitionMeta meta = new PartitionMeta();

        assertThat(meta.lastReplicationProtocolGroupConfigFirstPageId(), is(0L));

        meta.lastReplicationProtocolGroupConfigFirstPageId(null, 12);

        assertThat(meta.lastReplicationProtocolGroupConfigFirstPageId(), is(12L));

        meta.lastReplicationProtocolGroupConfigFirstPageId(UUID.randomUUID(), 34);

        assertThat(meta.lastReplicationProtocolGroupConfigFirstPageId(), is(34L));
    }

    @Test
    void testPageCount() {
        PartitionMeta meta = new PartitionMeta();

        assertEquals(0, meta.pageCount());

        assertDoesNotThrow(() -> meta.incrementPageCount(null));

        assertEquals(1, meta.pageCount());

        assertDoesNotThrow(() -> meta.incrementPageCount(UUID.randomUUID()));

        assertEquals(2, meta.pageCount());
    }

    @Test
    void testVersionChainTreeRootPageId() {
        PartitionMeta meta = new PartitionMeta();

        assertEquals(0, meta.versionChainTreeRootPageId());

        assertDoesNotThrow(() -> meta.versionChainTreeRootPageId(null, 100));

        assertEquals(100, meta.versionChainTreeRootPageId());

        assertDoesNotThrow(() -> meta.versionChainTreeRootPageId(UUID.randomUUID(), 500));

        assertEquals(500, meta.versionChainTreeRootPageId());
    }

    @Test
    void testFreeListRootPageId() {
        PartitionMeta meta = new PartitionMeta();

        assertEquals(0, meta.freeListRootPageId());

        assertDoesNotThrow(() -> meta.freeListRootPageId(null, 100));

        assertEquals(100, meta.freeListRootPageId());

        assertDoesNotThrow(() -> meta.freeListRootPageId(UUID.randomUUID(), 500));

        assertEquals(500, meta.freeListRootPageId());
    }

    @Test
    void testSnapshot() {
        UUID checkpointId = null;

        PartitionMeta meta = new PartitionMeta(checkpointId, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        checkSnapshot(meta.metaSnapshot(checkpointId), 0, 0, 0, 0, 0, 0);
        checkSnapshot(meta.metaSnapshot(checkpointId = UUID.randomUUID()), 0, 0, 0, 0, 0, 0);

        meta.lastApplied(checkpointId, 50, 5);
        meta.lastReplicationProtocolGroupConfigFirstPageId(checkpointId, 12);
        meta.versionChainTreeRootPageId(checkpointId, 300);
        meta.freeListRootPageId(checkpointId, 900);
        meta.incrementPageCount(checkpointId);

        checkSnapshot(meta.metaSnapshot(checkpointId), 0, 0, 0, 0, 0, 0);
        checkSnapshot(meta.metaSnapshot(UUID.randomUUID()), 50, 5, 12, 300, 900, 1);

        meta.lastApplied(checkpointId = UUID.randomUUID(), 51, 6);
        meta.lastReplicationProtocolGroupConfigFirstPageId(checkpointId, 34);
        checkSnapshot(meta.metaSnapshot(checkpointId), 50, 5, 12, 300, 900, 1);

        meta.versionChainTreeRootPageId(checkpointId = UUID.randomUUID(), 303);
        checkSnapshot(meta.metaSnapshot(checkpointId), 51, 6, 34, 300, 900, 1);

        meta.freeListRootPageId(checkpointId = UUID.randomUUID(), 909);
        checkSnapshot(meta.metaSnapshot(checkpointId), 51, 6, 34, 303, 900, 1);

        meta.incrementPageCount(checkpointId = UUID.randomUUID());
        checkSnapshot(meta.metaSnapshot(checkpointId), 51, 6, 34, 303, 909, 1);

        checkSnapshot(meta.metaSnapshot(UUID.randomUUID()), 51, 6, 34, 303, 909, 2);
    }

    @Test
    void testPartitionMetaPageId() {
        long pageId = partitionMetaPageId(666);

        assertEquals(666, partitionId(pageId));
        assertEquals(FLAG_AUX, flag(pageId));
        assertEquals(0, pageIndex(pageId));
    }

    private static void checkSnapshot(
            PartitionMetaSnapshot snapshot,
            long expLastAppliedIndex,
            long expLastAppliedTerm,
            long expLastGroupConfigFirstPageId,
            long expVersionChainTreeRootPageId,
            long expFreeListRootPageId,
            int expPageCount
    ) {
        assertThat(snapshot.lastAppliedIndex(), equalTo(expLastAppliedIndex));
        assertThat(snapshot.lastAppliedTerm(), equalTo(expLastAppliedTerm));
        assertThat(snapshot.lastReplicationProtocolGroupConfigFirstPageId(), equalTo(expLastGroupConfigFirstPageId));
        assertThat(snapshot.versionChainTreeRootPageId(), equalTo(expVersionChainTreeRootPageId));
        assertThat(snapshot.freeListRootPageId(), equalTo(expFreeListRootPageId));
        assertThat(snapshot.pageCount(), equalTo(expPageCount));
    }
}
