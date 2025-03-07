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

package org.apache.ignite.internal.eventlog.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ignite.internal.eventlog.api.Event;
import org.apache.ignite.internal.eventlog.api.Sink;
import org.jetbrains.annotations.VisibleForTesting;

class InMemoryCollectionSink implements Sink {
    private final CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();

    private final AtomicInteger stopCounter = new AtomicInteger(0);

    @Override
    public void write(Event event) {
        events.add(event);
    }

    @Override
    public void stop() {
        stopCounter.incrementAndGet();

        Sink.super.stop();
    }

    @VisibleForTesting
    List<Event> events() {
        return new ArrayList<>(events);
    }

    public int getStopCounter() {
        return stopCounter.get();
    }
}
