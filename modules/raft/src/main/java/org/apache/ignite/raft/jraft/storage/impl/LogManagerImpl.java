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
package org.apache.ignite.raft.jraft.storage.impl;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.raft.storage.TermCache;
import org.apache.ignite.raft.jraft.FSMCaller;
import org.apache.ignite.raft.jraft.Status;
import org.apache.ignite.raft.jraft.conf.Configuration;
import org.apache.ignite.raft.jraft.conf.ConfigurationEntry;
import org.apache.ignite.raft.jraft.conf.ConfigurationManager;
import org.apache.ignite.raft.jraft.core.NodeMetrics;
import org.apache.ignite.raft.jraft.disruptor.NodeIdAware;
import org.apache.ignite.raft.jraft.disruptor.StripedDisruptor;
import org.apache.ignite.raft.jraft.entity.EnumOutter.EntryType;
import org.apache.ignite.raft.jraft.entity.EnumOutter.ErrorType;
import org.apache.ignite.raft.jraft.entity.LogEntry;
import org.apache.ignite.raft.jraft.entity.LogId;
import org.apache.ignite.raft.jraft.entity.NodeId;
import org.apache.ignite.raft.jraft.entity.PeerId;
import org.apache.ignite.raft.jraft.entity.RaftOutter.SnapshotMeta;
import org.apache.ignite.raft.jraft.error.LogEntryCorruptedException;
import org.apache.ignite.raft.jraft.error.RaftError;
import org.apache.ignite.raft.jraft.error.RaftException;
import org.apache.ignite.raft.jraft.option.LogManagerOptions;
import org.apache.ignite.raft.jraft.option.LogStorageOptions;
import org.apache.ignite.raft.jraft.option.NodeOptions;
import org.apache.ignite.raft.jraft.option.RaftOptions;
import org.apache.ignite.raft.jraft.storage.LogManager;
import org.apache.ignite.raft.jraft.storage.LogStorage;
import org.apache.ignite.raft.jraft.util.ArrayDeque;
import org.apache.ignite.raft.jraft.util.DisruptorMetricSet;
import org.apache.ignite.raft.jraft.util.Requires;
import org.apache.ignite.raft.jraft.util.SegmentList;
import org.apache.ignite.raft.jraft.util.Utils;

/**
 * LogManager implementation.
 */
public class LogManagerImpl implements LogManager {
    private static final IgniteLogger LOG = Loggers.forClass(LogManagerImpl.class);

    /** Raft node id. */
    private NodeId nodeId;

    private LogStorage logStorage;
    private ConfigurationManager configManager;
    private FSMCaller fsmCaller;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock writeLock = this.lock.writeLock();
    private final Lock readLock = this.lock.readLock();
    private volatile boolean stopped;
    protected volatile boolean hasError;
    private long nextWaitId;
    private LogId diskId = new LogId(0, 0); // Last log entry written to disk.
    private LogId appliedId = new LogId(0, 0);
    private final SegmentList<LogEntry> logsInMemory = new SegmentList<>(true);
    private final TermCache termCache = new TermCache(8);
    private volatile long firstLogIndex;
    private volatile long lastLogIndex;
    private volatile LogId lastSnapshotId = new LogId(0, 0);
    private final Map<Long, WaitMeta> waitMap = new HashMap<>();
    private StripedDisruptor<StableClosureEvent> disruptor;
    private RingBuffer<StableClosureEvent> diskQueue;
    private RaftOptions raftOptions;
    private volatile CountDownLatch shutDownLatch;
    private NodeMetrics nodeMetrics;
    private final CopyOnWriteArrayList<LastLogIndexListener> lastLogIndexListeners = new CopyOnWriteArrayList<>();
    private NodeOptions nodeOptions;

    private enum EventType {
        OTHER, // other event type.
        RESET, // reset
        TRUNCATE_PREFIX, // truncate log from prefix
        TRUNCATE_SUFFIX, // truncate log from suffix
        SHUTDOWN, //
        LAST_LOG_ID // get last log id
    }

    public static class StableClosureEvent extends NodeIdAware {
        StableClosure done;
        EventType type;

        @Override
        public void reset() {
            super.reset();

            this.done = null;
            this.type = null;
        }
    }

    /**
     * Waiter metadata
     */
    private static class WaitMeta {
        /**
         * callback when new log come in
         */
        NewLogCallback onNewLog;

        /**
         * callback error code
         */
        int errorCode;

        /**
         * the waiter pass-in argument
         */
        Object arg;

        WaitMeta(final NewLogCallback onNewLog, final Object arg, final int errorCode) {
            super();
            this.onNewLog = onNewLog;
            this.arg = arg;
            this.errorCode = errorCode;
        }

    }

    @Override
    public void addLastLogIndexListener(final LastLogIndexListener listener) {
        this.lastLogIndexListeners.add(listener);

    }

    @Override
    public void removeLastLogIndexListener(final LastLogIndexListener listener) {
        this.lastLogIndexListeners.remove(listener);
    }

    @Override
    public boolean init(final LogManagerOptions opts) {
        this.writeLock.lock();
        try {
            if (opts.getLogStorage() == null) {
                LOG.error("Fail to init log manager, log storage is null");
                return false;
            }
            this.raftOptions = opts.getRaftOptions();
            this.nodeMetrics = opts.getNodeMetrics();
            this.logStorage = opts.getLogStorage();
            this.configManager = opts.getConfigurationManager();
            this.nodeOptions = opts.getNode().getOptions();
            this.nodeId = opts.getNode().getNodeId();

            LogStorageOptions lsOpts = new LogStorageOptions();
            lsOpts.setConfigurationManager(this.configManager);
            lsOpts.setLogEntryCodecFactory(opts.getLogEntryCodecFactory());

            if (!this.logStorage.init(lsOpts)) {
                LOG.error("Fail to init logStorage");
                return false;
            }
            this.firstLogIndex = this.logStorage.getFirstLogIndex();
            this.lastLogIndex = this.logStorage.getLastLogIndex();
            this.diskId = new LogId(this.lastLogIndex, getTermFromLogStorage(this.lastLogIndex));
            this.fsmCaller = opts.getFsmCaller();
            this.disruptor = opts.getLogManagerDisruptor();

            this.diskQueue = disruptor.subscribe(this.nodeId, new StableClosureEventHandler(),
                (event, ex) -> reportError(-1, "LogManager handle event error"));

            if (this.nodeMetrics.getMetricRegistry() != null) {
                this.nodeMetrics.getMetricRegistry().register("jraft-log-manager-disruptor",
                    new DisruptorMetricSet(this.diskQueue));
            }
        }
        finally {
            this.writeLock.unlock();
        }
        return true;
    }

    @Override
    public boolean hasAvailableCapacityToAppendEntries(final int requiredCapacity) {
        if (this.stopped) {
            return false;
        }
        return this.diskQueue.hasAvailableCapacity(requiredCapacity);
    }

    private void stopDiskThread() {
        if (this.diskQueue == null)
            return; // Was not started.

        this.shutDownLatch = new CountDownLatch(1);
        Utils.runInThread(nodeOptions.getCommonExecutor(), () -> this.diskQueue.publishEvent((event, sequence) -> {
            event.reset();

            event.nodeId = this.nodeId;
            event.type = EventType.SHUTDOWN;
        }));
    }

    @Override
    public void join() throws InterruptedException {
        if (this.shutDownLatch == null) {
            return;
        }
        this.shutDownLatch.await();
        this.disruptor.unsubscribe(this.nodeId);
    }

    @Override
    public void shutdown() {
        boolean doUnlock = true;
        this.writeLock.lock();
        try {
            if (this.stopped) {
                return;
            }
            this.stopped = true;
            doUnlock = false;
            wakeupAllWaiter(this.writeLock);
        }
        finally {
            if (doUnlock) {
                this.writeLock.unlock();
            }
        }
        stopDiskThread();
    }

    private void clearMemoryLogs(final LogId id) {
        this.writeLock.lock();
        try {
            this.logsInMemory.removeFromFirstWhen(entry -> entry.getId().compareTo(id) <= 0);
        }
        finally {
            this.writeLock.unlock();
        }
    }

    private static class LastLogIdClosure extends StableClosure {
        LastLogIdClosure() {
            super(null);
        }

        private LogId lastLogId;

        void setLastLogId(final LogId logId) {
            Requires.requireTrue(logId.getIndex() == 0 || logId.getTerm() != 0);
            this.lastLogId = logId;
        }

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void run(final Status status) {
            this.latch.countDown();
        }

        void await() throws InterruptedException {
            this.latch.await();
        }

    }

    @Override
    public void appendEntries(final List<LogEntry> entries, final StableClosure done) {
        assert(done != null);

        Requires.requireNonNull(done, "done");
        if (this.hasError) {
            entries.clear();
            Utils.runClosureInThread(nodeOptions.getCommonExecutor(), done, new Status(RaftError.EIO, "Corrupted LogStorage"));
            return;
        }
        boolean doUnlock = true;
        this.writeLock.lock();
        try {
            if (!entries.isEmpty() && !checkAndResolveConflict(entries, done, this.writeLock)) {
                // If checkAndResolveConflict returns false, the done will be called in it.
                entries.clear();
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                final LogEntry entry = entries.get(i);
                // Set checksum after checkAndResolveConflict
                if (this.raftOptions.isEnableLogEntryChecksum()) {
                    entry.setChecksum(entry.checksum());
                }
                if (entry.getType() == EntryType.ENTRY_TYPE_CONFIGURATION) {
                    Configuration oldConf = new Configuration();
                    if (entry.getOldPeers() != null) {
                        oldConf = new Configuration(entry.getOldPeers(), entry.getOldLearners());
                    }
                    final ConfigurationEntry conf = new ConfigurationEntry(entry.getId(),
                        new Configuration(entry.getPeers(), entry.getLearners()), oldConf);
                    this.configManager.add(conf);
                }
            }
            if (!entries.isEmpty()) {
                done.setFirstLogIndex(entries.get(0).getId().getIndex());
                this.logsInMemory.addAll(entries);

                for (LogEntry entry : entries) {
                    this.termCache.append(entry.getId());
                }
            }
            done.setEntries(entries);

            doUnlock = false;
            if (!wakeupAllWaiter(this.writeLock)) {
                notifyLastLogIndexListeners();
            }

            // publish event out of lock
            this.diskQueue.publishEvent((event, sequence) -> {
              event.reset();

              event.nodeId = this.nodeId;
              event.type = EventType.OTHER;
              event.done = done;
            });
        }
        finally {
            if (doUnlock) {
                this.writeLock.unlock();
            }
        }
    }

    /**
     * Adds event to disk queue, NEVER call it in lock.
     * @param done
     * @param type
     */
    private void offerEvent(final StableClosure done, final EventType type) {
        assert(done != null);

        if (this.stopped) {
            Utils.runClosureInThread(nodeOptions.getCommonExecutor(), done, new Status(RaftError.ESTOP, "Log manager is stopped."));
            return;
        }
        this.diskQueue.publishEvent((event, sequence) -> {
            event.reset();

            event.nodeId = this.nodeId;
            event.type = type;
            event.done = done;
        });
    }

    private void notifyLastLogIndexListeners() {
        for (LastLogIndexListener listener : lastLogIndexListeners) {
            if (listener != null) {
                try {
                    listener.onLastLogIndexChanged(this.lastLogIndex);
                }
                catch (final Exception e) {
                    LOG.error("Fail to notify LastLogIndexListener, listener={}, index={}", listener, this.lastLogIndex);
                }
            }
        }
    }

    private boolean wakeupAllWaiter(final Lock lock) {
        if (this.waitMap.isEmpty()) {
            lock.unlock();
            return false;
        }
        final List<WaitMeta> wms = new ArrayList<>(this.waitMap.values());
        final int errCode = this.stopped ? RaftError.ESTOP.getNumber() : RaftError.SUCCESS.getNumber();
        this.waitMap.clear();
        lock.unlock();

        final int waiterCount = wms.size();
        for (int i = 0; i < waiterCount; i++) {
            final WaitMeta wm = wms.get(i);
            wm.errorCode = errCode;
            Utils.runInThread(nodeOptions.getCommonExecutor(), () -> runOnNewLog(wm));
        }
        return true;
    }

    protected LogId appendToStorage(final List<LogEntry> toAppend) {
        LogId lastId = null;
        if (!this.hasError) {
            final long startMs = Utils.monotonicMs();
            final int entriesCount = toAppend.size();
            this.nodeMetrics.recordSize("append-logs-count", entriesCount);
            try {
                int writtenSize = 0;
                for (int i = 0; i < entriesCount; i++) {
                    final LogEntry entry = toAppend.get(i);
                    writtenSize += entry.getData() != null ? entry.getData().remaining() : 0;
                }
                this.nodeMetrics.recordSize("append-logs-bytes", writtenSize);
                final int nAppent = appendToLogStorage(toAppend);
                if (nAppent != entriesCount) {
                    LOG.error("**Critical error**, fail to appendEntries, nAppent={}, toAppend={}", nAppent,
                        toAppend.size());
                    reportError(RaftError.EIO.getNumber(), "Fail to append log entries");
                }
                if (nAppent > 0) {
                    lastId = toAppend.get(nAppent - 1).getId();
                }
            }
            finally {
                this.nodeMetrics.recordLatency("append-logs", Utils.monotonicMs() - startMs);
            }
        }
        return lastId;
    }

    protected int appendToLogStorage(List<LogEntry> toAppend) {
        return this.logStorage.appendEntries(toAppend);
    }

    protected class AppendBatcher {
        protected final List<StableClosure> storage;
        protected final int cap;
        protected int size;
        protected int bufferSize;
        protected final List<LogEntry> toAppend;
        protected LogId lastId;

        protected AppendBatcher(final List<StableClosure> storage, final int cap, final List<LogEntry> toAppend,
            final LogId lastId) {
            super();
            this.storage = storage;
            this.cap = cap;
            this.toAppend = toAppend;
            this.lastId = lastId;
        }

        protected LogId flush() {
            if (this.size > 0) {
                this.lastId = appendToStorage(this.toAppend);
                for (int i = 0; i < this.size; i++) {
                    this.storage.get(i).getEntries().clear();
                    Status st = null;
                    try {
                        if (LogManagerImpl.this.hasError) {
                            st = new Status(RaftError.EIO, "Corrupted LogStorage");
                        }
                        else {
                            st = Status.OK();
                        }
                        this.storage.get(i).run(st);
                    }
                    catch (Throwable t) {
                        LOG.error("Fail to run closure with status: {}.", t, st);
                    }
                }
                this.toAppend.clear();
                this.storage.clear();

            }
            this.size = 0;
            this.bufferSize = 0;
            return this.lastId;
        }

        protected void append(final StableClosure done) {
            if (this.size == this.cap || this.bufferSize >= LogManagerImpl.this.raftOptions.getMaxAppendBufferSize()) {
                flush();
            }
            this.storage.add(done);
            this.size++;
            this.toAppend.addAll(done.getEntries());
            for (final LogEntry entry : done.getEntries()) {
                this.bufferSize += entry.getData() != null ? entry.getData().remaining() : 0;
            }
        }
    }

    protected AppendBatcher newAppendBatcher(List<StableClosure> storages, int cap, LogId diskId) {
        return new AppendBatcher(storages, cap, new ArrayList<>(), diskId);
    }

    private class StableClosureEventHandler implements EventHandler<StableClosureEvent> {
        LogId lastId = LogManagerImpl.this.diskId;
        List<StableClosure> storage = new ArrayList<>(256);
        AppendBatcher ab = newAppendBatcher(this.storage, 256, LogManagerImpl.this.diskId);

        @Override
        public void onEvent(final StableClosureEvent event, final long sequence, final boolean endOfBatch)
            throws Exception {
            if (event.type == EventType.SHUTDOWN) {
                this.lastId = this.ab.flush();
                setDiskId(this.lastId);
                LogManagerImpl.this.shutDownLatch.countDown();
                event.reset();
                return;
            }
            final StableClosure done = event.done;
            final EventType eventType = event.type;

            event.reset();

            if (done.getEntries() != null && !done.getEntries().isEmpty()) {
                this.ab.append(done);
            }
            else {
                this.lastId = this.ab.flush();
                setDiskId(this.lastId);

                boolean ret = true;
                switch (eventType) {
                    case LAST_LOG_ID:
                        ((LastLogIdClosure) done).setLastLogId(this.lastId.copy());
                        break;
                    case TRUNCATE_PREFIX:
                        long startMs = Utils.monotonicMs();
                        try {
                            final TruncatePrefixClosure tpc = (TruncatePrefixClosure) done;
                            LOG.info(
                                    "Truncating log storage prefix [groupId={}, firstIndexKept={}]",
                                    nodeId.getGroupId(),
                                    tpc.firstIndexKept
                            );
                            ret = LogManagerImpl.this.logStorage.truncatePrefix(tpc.firstIndexKept);
                        }
                        finally {
                            LogManagerImpl.this.nodeMetrics.recordLatency("truncate-log-prefix", Utils.monotonicMs()
                                - startMs);
                        }
                        break;
                    case TRUNCATE_SUFFIX:
                        startMs = Utils.monotonicMs();
                        try {
                            final TruncateSuffixClosure tsc = (TruncateSuffixClosure) done;
                            LOG.info(
                                    "Truncating log storage suffix [groupId={}, lastIndexKept={}]",
                                    nodeId.getGroupId(),
                                    tsc.lastIndexKept
                            );
                            ret = LogManagerImpl.this.logStorage.truncateSuffix(tsc.lastIndexKept);
                            if (ret) {
                                this.lastId.setIndex(tsc.lastIndexKept);
                                this.lastId.setTerm(tsc.lastTermKept);
                                Requires.requireTrue(this.lastId.getIndex() == 0 || this.lastId.getTerm() != 0);
                            }
                        }
                        finally {
                            LogManagerImpl.this.nodeMetrics.recordLatency("truncate-log-suffix", Utils.monotonicMs()
                                - startMs);
                        }
                        break;
                    case RESET:
                        final ResetClosure rc = (ResetClosure) done;
                        LOG.info("Resetting log storage [groupId={}, nextLogIndex={}]", nodeId.getGroupId(), rc.nextLogIndex);
                        ret = LogManagerImpl.this.logStorage.reset(rc.nextLogIndex);
                        break;
                    default:
                        break;
                }

                if (!ret) {
                    reportError(RaftError.EIO.getNumber(), "Failed operation in LogStorage");
                }
                else {
                    done.run(Status.OK());
                }
            }

            if (endOfBatch) {
                this.lastId = this.ab.flush();
                setDiskId(this.lastId);
            }
        }

    }

    protected void reportError(final int code, final String fmt, final Object... args) {
        this.hasError = true;
        final RaftException error = new RaftException(ErrorType.ERROR_TYPE_LOG);
        error.setStatus(new Status(code, fmt, args));
        this.fsmCaller.onError(error);
    }

    protected void setDiskId(final LogId id) {
        if (id == null) {
            return;
        }
        LogId clearId;
        this.writeLock.lock();
        try {
            if (id.compareTo(this.diskId) < 0) {
                return;
            }
            this.diskId = id;
            clearId = this.diskId.compareTo(this.appliedId) <= 0 ? this.diskId : this.appliedId;
        }
        finally {
            this.writeLock.unlock();
        }
        if (clearId != null) {
            clearMemoryLogs(clearId);
        }
    }

    @Override
    public void setSnapshot(final SnapshotMeta meta, boolean useLastSnapshotIndex) {
        LOG.debug("set snapshot: {}.", meta);
        boolean doUnlock = true;
        this.writeLock.lock();
        try {
            if (meta.lastIncludedIndex() <= this.lastSnapshotId.getIndex()) {
                return;
            }
            final Configuration conf = confFromMeta(meta);
            final Configuration oldConf = oldConfFromMeta(meta);

            final ConfigurationEntry entry = new ConfigurationEntry(new LogId(meta.lastIncludedIndex(),
                meta.lastIncludedTerm()), conf, oldConf);
            this.configManager.setSnapshot(entry);
            final long term = unsafeGetTerm(meta.lastIncludedIndex());
            final long savedLastSnapshotIndex = this.lastSnapshotId.getIndex();

            this.lastSnapshotId.setIndex(meta.lastIncludedIndex());
            this.lastSnapshotId.setTerm(meta.lastIncludedTerm());

            if (this.lastSnapshotId.compareTo(this.appliedId) > 0) {
                this.appliedId = this.lastSnapshotId.copy();
            }
            // NOTICE: not to update disk_id here as we are not sure if this node really
            // has these logs on disk storage. Just leave disk_id as it was, which can keep
            // these logs in memory all the time until they are flushed to disk. By this
            // way we can avoid some corner cases which failed to get logs.
            // See https://github.com/baidu/braft/pull/224/commits/8ef6fdbf70d23f5a4ee147356a889e2c0fa22aac
            //            if (this.lastSnapshotId.compareTo(this.diskId) > 0) {
            //                this.diskId = this.lastSnapshotId.copy();
            //            }

            if (useLastSnapshotIndex || term == 0) {
                // last_included_index is larger than last_index
                // FIXME: what if last_included_index is less than first_index?
                doUnlock = false;
                //unlock in truncatePrefix
                truncatePrefix(meta.lastIncludedIndex() + 1, this.writeLock);
            }
            else if (term == meta.lastIncludedTerm()) {
                // Truncating log to the index of the last snapshot.
                // We don't truncate log before the last snapshot immediately since
                // some log around last_snapshot_index is probably needed by some
                // followers
                // TODO if there are still be need? TODO asch
                if (savedLastSnapshotIndex > 0) {
                    doUnlock = false;
                    //unlock in truncatePrefix
                    truncatePrefix(savedLastSnapshotIndex + 1, this.writeLock);
                }
            }
            else {
                if (!reset(meta.lastIncludedIndex() + 1)) {
                    LOG.warn("Reset log manager failed, nextLogIndex={}.", meta.lastIncludedIndex() + 1);
                }
            }
        }
        finally {
            if (doUnlock) {
                this.writeLock.unlock();
            }
        }

    }

    private Configuration oldConfFromMeta(final SnapshotMeta meta) {
        final Configuration oldConf = new Configuration();

        if (meta.oldPeersList() != null) {
            for (String oldPeer : meta.oldPeersList()) {
                final PeerId peer = new PeerId();
                peer.parse(oldPeer);
                oldConf.addPeer(peer);
            }
        }

        if (meta.oldLearnersList() != null) {
            for (String oldLearner : meta.oldLearnersList()) {
                final PeerId peer = new PeerId();
                peer.parse(oldLearner);
                oldConf.addLearner(peer);
            }
        }

        return oldConf;
    }

    private Configuration confFromMeta(final SnapshotMeta meta) {
        final Configuration conf = new Configuration();
        if (meta.peersList() != null) {
            for (String metaPeer : meta.peersList()) {
                final PeerId peer = new PeerId();
                peer.parse(metaPeer);
                conf.addPeer(peer);
            }
        }

        if (meta.learnersList() != null) {
            for (String learner : meta.learnersList()) {
                final PeerId peer = new PeerId();
                peer.parse(learner);
                conf.addLearner(peer);
            }
        }

        return conf;
    }

    @Override
    public void clearBufferedLogs() {
        boolean doUnlock = true;
        this.writeLock.lock();
        try {
            if (this.lastSnapshotId.getIndex() != 0) {
                doUnlock = false;
                //unlock in truncatePrefix
                truncatePrefix(this.lastSnapshotId.getIndex() + 1, this.writeLock);
            }
        }
        finally {
            if (doUnlock) {
                this.writeLock.unlock();
            }
        }
    }

    private String descLogsInMemory() {
        final StringBuilder sb = new StringBuilder();
        boolean wasFirst = true;
        for (int i = 0; i < this.logsInMemory.size(); i++) {
            LogEntry logEntry = this.logsInMemory.get(i);
            if (!wasFirst) {
                sb.append(",");
            }
            else {
                wasFirst = false;
            }
            sb.append("<id:(").append(logEntry.getId().getTerm()).append(",").append(logEntry.getId().getIndex())
                .append("),type:").append(logEntry.getType()).append(">");
        }
        return sb.toString();
    }

    protected LogEntry getEntryFromMemory(final long index) {
        LogEntry entry = null;
        if (!this.logsInMemory.isEmpty()) {
            final long firstIndex = this.logsInMemory.peekFirst().getId().getIndex();
            final long lastIndex = this.logsInMemory.peekLast().getId().getIndex();
            if (lastIndex - firstIndex + 1 != this.logsInMemory.size()) {
                throw new IllegalStateException(String.format("lastIndex=%d,firstIndex=%d,logsInMemory=[%s]",
                    lastIndex, firstIndex, descLogsInMemory()));
            }
            if (index >= firstIndex && index <= lastIndex) {
                entry = this.logsInMemory.get((int) (index - firstIndex));
            }
        }
        return entry;
    }

    @Override
    public LogEntry getEntry(final long index) {
        this.readLock.lock();
        try {
            if (index > this.lastLogIndex || index < this.firstLogIndex) {
                return null;
            }
            final LogEntry entry = getEntryFromMemory(index);
            if (entry != null) {
                return entry;
            }
        }
        finally {
            this.readLock.unlock();
        }
        final LogEntry entry = this.logStorage.getEntry(index);
        if (entry == null) {
            reportError(RaftError.EIO.getNumber(), "Corrupted entry at index=%d, not found", index);
        }
        // Validate checksum
        if (entry != null && this.raftOptions.isEnableLogEntryChecksum() && entry.isCorrupted()) {
            String msg = String.format("Corrupted entry at index=%d, term=%d, expectedChecksum=%d, realChecksum=%d",
                index, entry.getId().getTerm(), entry.getChecksum(), entry.checksum());
            // Report error to node and throw exception.
            reportError(RaftError.EIO.getNumber(), msg);
            throw new LogEntryCorruptedException(msg);
        }
        return entry;
    }

    @Override
    public long getTerm(final long index) {
        if (index == 0) {
            return 0;
        }
        this.readLock.lock();
        try {
            // check index equal snapshot_index, return snapshot_term
            if (index == this.lastSnapshotId.getIndex()) {
                return this.lastSnapshotId.getTerm();
            }
            // out of range, direct return 0
            if (index > this.lastLogIndex || index < this.firstLogIndex) {
                return 0;
            }

            long term = termCache.lookup(index);
            if (term != -1) {
                return term;
            }
        }
        finally {
            this.readLock.unlock();
        }
        return getTermFromLogStorage(index);
    }

    private long getTermFromLogStorage(final long index) {
        final LogEntry entry = this.logStorage.getEntry(index);
        if (entry != null) {
            if (this.raftOptions.isEnableLogEntryChecksum() && entry.isCorrupted()) {
                // Report error to node and throw exception.
                final String msg = String.format(
                    "The log entry is corrupted, index=%d, term=%d, expectedChecksum=%d, realChecksum=%d", entry
                        .getId().getIndex(), entry.getId().getTerm(), entry.getChecksum(), entry.checksum());
                reportError(RaftError.EIO.getNumber(), msg);
                throw new LogEntryCorruptedException(msg);
            }

            return entry.getId().getTerm();
        }
        return 0;
    }

    @Override
    public long getFirstLogIndex() {
        this.readLock.lock();
        try {
            return this.firstLogIndex;
        }
        finally {
            this.readLock.unlock();
        }
    }

    @Override
    public long getLastLogIndex() {
        return getLastLogIndex(false);
    }

    @Override
    public long getLastLogIndex(final boolean isFlush) {
        LastLogIdClosure c;
        this.readLock.lock();
        try {
            if (!isFlush) {
                return this.lastLogIndex;
            }
            else {
                if (this.lastLogIndex == this.lastSnapshotId.getIndex()) {
                    return this.lastLogIndex;
                }
                c = new LastLogIdClosure();
            }
        }
        finally {
            this.readLock.unlock();
        }
        offerEvent(c, EventType.LAST_LOG_ID);
        try {
            c.await();
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        if (c.lastLogId == null) {
            assert stopped : "Last log id can be null only when node is stopping.";

            throw new IllegalStateException("Node is shutting down");
        }

        return c.lastLogId.getIndex();
    }

    private long unsafeGetTerm(final long index) {
        if (index == 0) {
            return 0;
        }

        final LogId lss = this.lastSnapshotId;
        if (index == lss.getIndex()) {
            return lss.getTerm();
        }
        if (index > this.lastLogIndex || index < this.firstLogIndex) {
            return 0;
        }

        long term = termCache.lookup(index);
        if (term != -1) {
            return term;
        }

        return getTermFromLogStorage(index);
    }

    @Override
    public LogId getLastLogId(final boolean isFlush) {
        LastLogIdClosure c;
        this.readLock.lock();
        try {
            if (!isFlush) {
                if (this.lastLogIndex >= this.firstLogIndex) {
                    return new LogId(this.lastLogIndex, unsafeGetTerm(this.lastLogIndex));
                }
                return this.lastSnapshotId;
            }
            else {
                if (this.lastLogIndex == this.lastSnapshotId.getIndex()) {
                    return this.lastSnapshotId;
                }
                c = new LastLogIdClosure();
            }
        }
        finally {
            this.readLock.unlock();
        }
        offerEvent(c, EventType.LAST_LOG_ID);
        try {
            c.await(); // TODO FIXME asch https://issues.apache.org/jira/browse/IGNITE-15974
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (c.lastLogId == null) {
            assert stopped : "Last log id can be null only when node is stopping.";

            throw new IllegalStateException("Node is shutting down");
        }

        return c.lastLogId;
    }

    private static class TruncatePrefixClosure extends StableClosure {
        long firstIndexKept;

        TruncatePrefixClosure(final long firstIndexKept) {
            super(null);
            this.firstIndexKept = firstIndexKept;
        }

        @Override
        public void run(final Status status) {

        }

    }

    private static class TruncateSuffixClosure extends StableClosure {
        long lastIndexKept;
        long lastTermKept;

        TruncateSuffixClosure(final long lastIndexKept, final long lastTermKept) {
            super(null);
            this.lastIndexKept = lastIndexKept;
            this.lastTermKept = lastTermKept;
        }

        @Override
        public void run(final Status status) {

        }

    }

    private static class ResetClosure extends StableClosure {
        long nextLogIndex;

        ResetClosure(final long nextLogIndex) {
            super(null);
            this.nextLogIndex = nextLogIndex;
        }

        @Override
        public void run(final Status status) {

        }
    }

    private boolean truncatePrefix(final long firstIndexKept, final Lock lock) {

        this.logsInMemory.removeFromFirstWhen(entry -> entry.getId().getIndex() < firstIndexKept);

        // TODO  maybe it's fine here
        Requires.requireTrue(firstIndexKept >= this.firstLogIndex,
            "Try to truncate logs before %d, but the firstLogIndex is %d", firstIndexKept, this.firstLogIndex);

        this.firstLogIndex = firstIndexKept;
        if (firstIndexKept > this.lastLogIndex) {
            // The entry log is dropped
            this.lastLogIndex = firstIndexKept - 1;
        }
        LOG.debug("Truncate prefix, firstIndexKept is :{}", firstIndexKept);
        this.configManager.truncatePrefix(firstIndexKept);
        lock.unlock();
        final TruncatePrefixClosure c = new TruncatePrefixClosure(firstIndexKept);
        offerEvent(c, EventType.TRUNCATE_PREFIX);
        return true;
    }

    private boolean reset(final long nextLogIndex) {
        this.writeLock.lock();
        try {
            this.logsInMemory.clear();
            this.termCache.reset();
            this.firstLogIndex = nextLogIndex;
            this.lastLogIndex = nextLogIndex - 1;
            this.configManager.truncatePrefix(this.firstLogIndex);
            this.configManager.truncateSuffix(this.lastLogIndex);
            return true;
        }
        finally {
            this.writeLock.unlock();
            final ResetClosure c = new ResetClosure(nextLogIndex);
            offerEvent(c, EventType.RESET);
        }
    }

    private void unsafeTruncateSuffix(final long lastIndexKept, final Lock lock) {
        if (lastIndexKept < this.appliedId.getIndex()) {
            LOG.error("FATAL ERROR: Can't truncate logs before appliedId={}, lastIndexKept={}", this.appliedId,
                lastIndexKept);
            return;
        }

        this.logsInMemory.removeFromLastWhen(entry -> entry.getId().getIndex() > lastIndexKept);
        termCache.truncateTail(lastIndexKept + 1);

        this.lastLogIndex = lastIndexKept;
        final long lastTermKept = unsafeGetTerm(lastIndexKept);
        Requires.requireTrue(this.lastLogIndex == 0 || lastTermKept != 0);
        LOG.debug("Truncate suffix :{}", lastIndexKept);
        this.configManager.truncateSuffix(lastIndexKept);
        lock.unlock();
        final TruncateSuffixClosure c = new TruncateSuffixClosure(lastIndexKept, lastTermKept);
        offerEvent(c, EventType.TRUNCATE_SUFFIX);
        lock.lock();
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private boolean checkAndResolveConflict(final List<LogEntry> entries, final StableClosure done, final Lock lock) {
        final LogEntry firstLogEntry = ArrayDeque.peekFirst(entries);
        if (firstLogEntry.getId().getIndex() == 0) {
            // Node is currently the leader and |entries| are from the user who
            // don't know the correct indexes the logs should assign to. So we have
            // to assign indexes to the appending entries
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).getId().setIndex(++this.lastLogIndex);
            }
            return true;
        }
        else {
            // Node is currently a follower and |entries| are from the leader. We
            // should check and resolve the conflicts between the local logs and
            // |entries|
            if (firstLogEntry.getId().getIndex() > this.lastLogIndex + 1) {
                Utils.runClosureInThread(nodeOptions.getCommonExecutor(), done, new Status(RaftError.EINVAL,
                    "There's gap between first_index=%d and last_log_index=%d", firstLogEntry.getId().getIndex(),
                    this.lastLogIndex));
                return false;
            }
            final long appliedIndex = this.appliedId.getIndex();
            final LogEntry lastLogEntry = ArrayDeque.peekLast(entries);
            if (lastLogEntry.getId().getIndex() <= appliedIndex) {
                LOG.warn(
                    "Received entries of which the lastLog={} is not greater than appliedIndex={}, return immediately with nothing changed.",
                    lastLogEntry.getId().getIndex(), appliedIndex);
                // Replicate old logs before appliedIndex should be considered successfully, response OK.
                Utils.runClosureInThread(nodeOptions.getCommonExecutor(), done);
                return false;
            }
            if (firstLogEntry.getId().getIndex() == this.lastLogIndex + 1) {
                // fast path
                this.lastLogIndex = lastLogEntry.getId().getIndex();
            }
            else {
                // Appending entries overlap the local ones. We should find if there
                // is a conflicting index from which we should truncate the local
                // ones.
                int conflictingIndex = 0;
                for (; conflictingIndex < entries.size(); conflictingIndex++) {
                    if (unsafeGetTerm(entries.get(conflictingIndex).getId().getIndex()) != entries
                        .get(conflictingIndex).getId().getTerm()) {
                        break;
                    }
                }
                if (conflictingIndex != entries.size()) {
                    if (entries.get(conflictingIndex).getId().getIndex() <= this.lastLogIndex) {
                        // Truncate all the conflicting entries to make local logs
                        // consensus with the leader.
                        unsafeTruncateSuffix(entries.get(conflictingIndex).getId().getIndex() - 1, lock);
                    }
                    this.lastLogIndex = lastLogEntry.getId().getIndex();
                } // else this is a duplicated AppendEntriesRequest, we have
                // nothing to do besides releasing all the entries
                if (conflictingIndex > 0) {
                    // Remove duplication
                    entries.subList(0, conflictingIndex).clear();
                }
            }
            return true;
        }
    }

    @Override
    public ConfigurationEntry getConfiguration(final long index) {
        this.readLock.lock();
        try {
            return this.configManager.get(index);
        }
        finally {
            this.readLock.unlock();
        }
    }

    @Override
    public ConfigurationEntry checkAndSetConfiguration(final ConfigurationEntry current) {
        if (current == null) {
            return null;
        }
        this.readLock.lock();
        try {
            final ConfigurationEntry lastConf = this.configManager.getLastConfiguration();
            if (lastConf != null && !lastConf.isEmpty() && !current.getId().equals(lastConf.getId())) {
                return lastConf;
            }
        }
        finally {
            this.readLock.unlock();
        }
        return current;
    }

    @Override
    public long wait(final long expectedLastLogIndex, final NewLogCallback cb, final Object arg) {
        final WaitMeta wm = new WaitMeta(cb, arg, 0);
        return notifyOnNewLog(expectedLastLogIndex, wm);
    }

    private long notifyOnNewLog(final long expectedLastLogIndex, final WaitMeta wm) {
        this.writeLock.lock();
        try {
            if (expectedLastLogIndex != this.lastLogIndex || this.stopped) {
                wm.errorCode = this.stopped ? RaftError.ESTOP.getNumber() : 0;
                Utils.runInThread(nodeOptions.getCommonExecutor(), () -> runOnNewLog(wm));
                return 0L;
            }
            if (this.nextWaitId == 0) { //skip 0
                ++this.nextWaitId;
            }
            final long waitId = this.nextWaitId++;
            this.waitMap.put(waitId, wm);
            return waitId;
        }
        finally {
            this.writeLock.unlock();
        }
    }

    @Override
    public boolean removeWaiter(final long id) {
        this.writeLock.lock();
        try {
            return this.waitMap.remove(id) != null;
        }
        finally {
            this.writeLock.unlock();
        }
    }

    @Override
    public void setAppliedId(final LogId appliedId) {
        LogId clearId;
        this.writeLock.lock();
        try {
            if (appliedId.compareTo(this.appliedId) < 0) {
                return;
            }
            this.appliedId = appliedId.copy();
            clearId = this.diskId.compareTo(this.appliedId) <= 0 ? this.diskId : this.appliedId;
        }
        finally {
            this.writeLock.unlock();
        }
        if (clearId != null) {
            clearMemoryLogs(clearId);
        }
    }

    void runOnNewLog(final WaitMeta wm) {
        wm.onNewLog.onNewLog(wm.arg, wm.errorCode);
    }

    @Override
    public Status checkConsistency() {
        this.readLock.lock();
        try {
            Requires.requireTrue(this.firstLogIndex > 0);
            Requires.requireTrue(this.lastLogIndex >= 0);
            if (this.lastSnapshotId.equals(new LogId(0, 0))) {
                if (this.firstLogIndex == 1) {
                    return Status.OK();
                }
                return new Status(RaftError.EIO, "Missing logs in (0, %d)", this.firstLogIndex);
            }
            else {
                if (this.lastSnapshotId.getIndex() >= this.firstLogIndex - 1
                    && this.lastSnapshotId.getIndex() <= this.lastLogIndex) {
                    return Status.OK();
                }
                return new Status(RaftError.EIO, "There's a gap between snapshot={%d, %d} and log=[%d, %d] ",
                    this.lastSnapshotId.toString(), this.lastSnapshotId.getTerm(), this.firstLogIndex,
                    this.lastLogIndex);
            }
        }
        finally {
            this.readLock.unlock();
        }
    }

    @Override
    public void describe(final Printer out) {
        final long _firstLogIndex;
        final long _lastLogIndex;
        final String _diskId;
        final String _appliedId;
        final String _lastSnapshotId;
        this.readLock.lock();
        try {
            _firstLogIndex = this.firstLogIndex;
            _lastLogIndex = this.lastLogIndex;
            _diskId = String.valueOf(this.diskId);
            _appliedId = String.valueOf(this.appliedId);
            _lastSnapshotId = String.valueOf(this.lastSnapshotId);
        }
        finally {
            this.readLock.unlock();
        }
        out.print("  storage: [") //
            .print(_firstLogIndex) //
            .print(", ") //
            .print(_lastLogIndex) //
            .println(']');
        out.print("  diskId: ") //
            .println(_diskId);
        out.print("  appliedId: ") //
            .println(_appliedId);
        out.print("  lastSnapshotId: ") //
            .println(_lastSnapshotId);
    }
}
