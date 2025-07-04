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

package org.apache.ignite.internal.network;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.IgniteCluster;
import org.jetbrains.annotations.Nullable;

/**
 * Cluster implementation.
 */
public class IgniteClusterImpl implements IgniteCluster {
    public final TopologyService topologyService;

    public IgniteClusterImpl(TopologyService topologyService) {
        this.topologyService = topologyService;
    }

    @Override
    public Collection<ClusterNode> nodes() {
        return topologyService.logicalTopologyMembers();
    }

    @Override
    public CompletableFuture<Collection<ClusterNode>> nodesAsync() {
        return completedFuture(topologyService.allMembers());
    }

    @Override
    public @Nullable ClusterNode localNode() {
        return topologyService.localMember();
    }
}
