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

import java.io.Serializable;
import java.util.UUID;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.tostring.S;

/**
 * The result of a replicated write intent switch request.
 */
public class WriteIntentSwitchReplicatedInfo implements Serializable {

    private static final long serialVersionUID = 8130171618503063413L;

    private final UUID txId;

    private final ReplicationGroupId partitionId;

    public WriteIntentSwitchReplicatedInfo(UUID txId, ReplicationGroupId partitionId) {
        this.txId = txId;
        this.partitionId = partitionId;
    }

    public UUID txId() {
        return txId;
    }

    public ReplicationGroupId partitionId() {
        return partitionId;
    }

    @Override
    public String toString() {
        return S.toString(WriteIntentSwitchReplicatedInfo.class, this);
    }
}
