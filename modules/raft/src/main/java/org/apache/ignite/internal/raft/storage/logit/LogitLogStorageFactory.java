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

package org.apache.ignite.internal.raft.storage.logit;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.raft.storage.LogStorageFactory;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.raft.jraft.option.RaftOptions;
import org.apache.ignite.raft.jraft.storage.LogStorage;
import org.apache.ignite.raft.jraft.storage.logit.option.StoreOptions;
import org.apache.ignite.raft.jraft.storage.logit.storage.LogitLogStorage;
import org.apache.ignite.raft.jraft.util.ExecutorServiceHelper;
import org.apache.ignite.raft.jraft.util.Requires;
import org.apache.ignite.raft.jraft.util.StringUtils;

/**
 * Log storage factory for {@link LogitLogStorage} instances.
 */
public class LogitLogStorageFactory implements LogStorageFactory {
    private static final IgniteLogger LOG = Loggers.forClass(LogitLogStorageFactory.class);

    private static final String LOG_DIR_PREFIX = "log-";

    /** Executor for shared storages. */
    private final ScheduledExecutorService checkpointExecutor;

    private final Path baseLogStoragesPath;

    private final StoreOptions storeOptions;

    /**
     * Constructor.
     *
     * @param baseLogStoragesPath Location of all log storages, created by this factory.
     * @param storeOptions Logit log storage options.
     */
    public LogitLogStorageFactory(Path baseLogStoragesPath, StoreOptions storeOptions) {
        this.baseLogStoragesPath = baseLogStoragesPath;
        this.storeOptions = storeOptions;
        checkpointExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("logit-checkpoint-executor", LOG)
        );
    }

    @Override
    public void start() {
    }

    @Override
    public LogStorage createLogStorage(String groupId, RaftOptions raftOptions) {
        Requires.requireTrue(StringUtils.isNotBlank(groupId), "Blank log storage uri.");

        Path storagePath = resolveLogStoragePath(groupId);

        return new LogitLogStorage(storagePath, storeOptions, raftOptions, checkpointExecutor);
    }

    @Override
    public void close() {
        ExecutorServiceHelper.shutdownAndAwaitTermination(checkpointExecutor);
    }

    private Path resolveLogStoragePath(String groupId) {
        return baseLogStoragesPath.resolve(LOG_DIR_PREFIX + groupId);
    }
}
