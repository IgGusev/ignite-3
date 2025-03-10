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

package org.apache.ignite.internal.tx.message;

import org.apache.ignite.internal.network.annotations.Transferable;
import org.apache.ignite.internal.replicator.message.ReplicationGroupIdMessage;
import org.apache.ignite.internal.tx.TransactionMeta;
import org.apache.ignite.internal.tx.TxStateMetaAbandoned;

/** Message for transferring a {@link TxStateMetaAbandoned}. */
@Transferable(TxMessageGroup.TX_STATE_META_ABANDONED_MESSAGE)
public interface TxStateMetaAbandonedMessage extends TxStateMetaMessage {
    /** Timestamp when the latest {@code ABANDONED} state set. */
    long lastAbandonedMarkerTs();

    /** Converts to {@link TxStateMetaAbandoned}. */
    default TxStateMetaAbandoned asTxStateMetaAbandoned() {
        ReplicationGroupIdMessage commitPartitionId = commitPartitionId();

        return new TxStateMetaAbandoned(
                txCoordinatorId(),
                commitPartitionId == null ? null : commitPartitionId.asReplicationGroupId()
        );
    }

    @Override
    default TransactionMeta asTransactionMeta() {
        return asTxStateMetaAbandoned();
    }
}
