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

package org.apache.ignite.internal.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ReadWriteLock with striping mechanics. Compared to {@link ReentrantReadWriteLock} it has slightly improved performance of
 * {@link ReadWriteLock#readLock()} operations at the cost of {@link ReadWriteLock#writeLock()} operations and memory consumption. It also
 * supports reentrancy semantics like {@link ReentrantReadWriteLock}.
 */
public class IgniteStripedReadWriteLock implements ReadWriteLock {
    /** Default concurrency. */
    private static final int CONCURRENCY = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /** Index generator. */
    private static final AtomicInteger IDX_GEN = new AtomicInteger();

    /** Index. */
    private static final ThreadLocal<Integer> IDX = ThreadLocal.withInitial(() -> IDX_GEN.incrementAndGet());

    /** Locks. */
    private final ReentrantReadWriteLock[] locks;

    /** Composite write lock. */
    private final WriteLock writeLock;

    /**
     * Creates a new instance with default concurrency level.
     */
    public IgniteStripedReadWriteLock() {
        this(CONCURRENCY);
    }

    /**
     * Creates a new instance with given concurrency level.
     *
     * @param concurrencyLvl Number of internal read locks.
     */
    public IgniteStripedReadWriteLock(int concurrencyLvl) {
        locks = new ReentrantReadWriteLock[concurrencyLvl];

        for (int i = 0; i < concurrencyLvl; i++) {
            locks[i] = new ReentrantReadWriteLock();
        }

        writeLock = new WriteLock();
    }

    /**
     * Gets current index.
     *
     * @return Index of current thread stripe.
     */
    private int curIdx() {
        int idx = IDX.get();

        return idx % locks.length;
    }

    /** {@inheritDoc} */
    @Override
    public Lock readLock() {
        return locks[curIdx()].readLock();
    }

    /**
     * Get a lock by stripe.
     *
     * @param idx Stripe index.
     * @return The lock.
     */
    public Lock readLock(int idx) {
        return locks[idx].readLock();
    }

    /** {@inheritDoc} */
    @Override
    public Lock writeLock() {
        return writeLock;
    }

    /**
     * Queries if the write lock is held by the current thread.
     *
     * @return {@code true} if the current thread holds the write lock and {@code false} otherwise
     */
    public boolean isWriteLockedByCurrentThread() {
        return locks[curIdx()].isWriteLockedByCurrentThread();
    }

    /**
     * Queries the number of reentrant read holds on this lock by the current thread.  A reader thread has a hold on a lock for each lock
     * action that is not matched by an unlock action.
     *
     * @return the number of holds on the read lock by the current thread, or zero if the read lock is not held by the current thread
     */
    public int getReadHoldCount() {
        return locks[curIdx()].getReadHoldCount();
    }

    /**
     * Write lock.
     */
    private class WriteLock implements Lock {
        /** {@inheritDoc} */
        @Override
        public void lock() {
            try {
                lock0(false);
            } catch (InterruptedException ignore) {
                assert false : "Should never happen";
            }
        }

        /** {@inheritDoc} */
        @Override
        public void lockInterruptibly() throws InterruptedException {
            lock0(true);
        }

        /** {@inheritDoc} */
        @Override
        public void unlock() {
            unlock0(locks.length - 1);
        }

        /**
         * Internal lock routine.
         *
         * @param canInterrupt Whether to acquire the lock interruptibly.
         * @throws InterruptedException If interrupted.
         */
        private void lock0(boolean canInterrupt) throws InterruptedException {
            int i = 0;

            try {
                for (; i < locks.length; i++) {
                    if (canInterrupt) {
                        locks[i].writeLock().lockInterruptibly();
                    } else {
                        locks[i].writeLock().lock();
                    }
                }
            } catch (InterruptedException e) {
                unlock0(i - 1);

                throw e;
            }
        }

        /**
         * Internal unlock routine.
         *
         * @param fromIdx Start index.
         */
        private void unlock0(int fromIdx) {
            for (int i = fromIdx; i >= 0; i--) {
                locks[i].writeLock().unlock();
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean tryLock() {
            int i = 0;

            try {
                for (; i < locks.length; i++) {
                    if (!locks[i].writeLock().tryLock()) {
                        break;
                    }
                }
            } finally {
                if (0 < i && i < locks.length) {
                    unlock0(i - 1);
                }
            }

            return i == locks.length;
        }

        /** {@inheritDoc} */
        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            int i = 0;

            long end = unit.toNanos(time) + System.nanoTime();

            try {
                for (; i < locks.length && System.nanoTime() < end; i++) {
                    if (!locks[i].writeLock().tryLock(time, unit)) {
                        break;
                    }
                }
            } finally {
                if (0 < i && i < locks.length) {
                    unlock0(i - 1);
                }
            }

            return i == locks.length;
        }

        /** {@inheritDoc} */
        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}

