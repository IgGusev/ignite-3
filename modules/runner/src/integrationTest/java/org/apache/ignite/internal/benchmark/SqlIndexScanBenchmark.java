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

package org.apache.ignite.internal.benchmark;

import static org.apache.ignite.internal.lang.IgniteStringFormatter.format;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.ignite.internal.util.SubscriptionUtils;
import org.apache.ignite.sql.IgniteSql;
import org.apache.ignite.table.DataStreamerItem;
import org.apache.ignite.table.DataStreamerOptions;
import org.apache.ignite.table.Tuple;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmark that compares sequential scanning of index against full table scan.
 */
@State(Scope.Benchmark)
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 20, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@SuppressWarnings({"WeakerAccess", "unused"})
public class SqlIndexScanBenchmark extends AbstractMultiNodeBenchmark {
    /*
        By default, cluster's work directory will be created as a temporary folder. This implies,
        that all data generated by benchmark will be cleared automatically. However, this also implies
        that cluster will be recreated on EVERY RUN. To initialize cluster once and then reuse it state
        override `AbstractMultiNodeBenchmark.workDir()` method. Don't forget to clear that directory
        afterwards.
     */

    private static final String DATASET_READY_MARK_FILE_NAME = "ready.txt";

    private static final String SELECT_STATEMENT_TEMPLATE = "SELECT {} t.* FROM test t WHERE val >= ? LIMIT ?";

    private static final int TABLE_SIZE = 1_500_000;
    private static final LocalDate INITIAL_DATE = LocalDate.of(1970, 1, 1);

    @Param({"1", "1000", "10000", "100000"})
    private int limit;

    @Param({"FIRST_N", "LAST_N"})
    private ScanMode scanMode;

    private IgniteSql sql;
    private LocalDate startDate;

    /** Initializes a schema and fills tables with data. */
    @Setup
    public void setUp() throws Exception {
        try {
            sql = publicIgnite.sql();

            if (!Files.exists(workDir().resolve(DATASET_READY_MARK_FILE_NAME))) {
                sql.executeScript(
                        "CREATE ZONE single_partition_zone (replicas 1, partitions 1) STORAGE PROFILES ['default'];"
                                + "CREATE TABLE test (id INT PRIMARY KEY, val DATE) ZONE single_partition_zone;"
                                + "CREATE INDEX test_val_idx ON test(val);"
                );

                CompletableFuture<?> result = publicIgnite.tables().table("test")
                        .recordView()
                        .streamData(SubscriptionUtils.fromIterable(() -> IntStream.range(0, TABLE_SIZE)
                                .mapToObj(i -> DataStreamerItem.of(Tuple.create()
                                        .set("id", i)
                                        .set("val", INITIAL_DATE.plusDays(i)))
                                ).iterator()), DataStreamerOptions.DEFAULT);

                result.get(15, TimeUnit.MINUTES);

                Files.createFile(workDir().resolve(DATASET_READY_MARK_FILE_NAME));
            }

            startDate = scanMode.valueForPredicate(limit);
        } catch (Exception e) {
            nodeTearDown();

            throw e;
        }
    }

    /** Measures performance of scan over a table. */
    @Benchmark
    public void forceTableScan(ForceTableScanState state, Blackhole bh) {
        try (var rs = sql.execute(null, state.query, startDate, limit)) {
            while (rs.hasNext()) {
                bh.consume(rs.next());
            }
        }
    }

    /** Measures performance of scan over an index. */
    @Benchmark
    public void forceIndexScan(ForceIndexScanState state, Blackhole bh) {
        try (var rs = sql.execute(null, state.query, startDate, limit)) {
            while (rs.hasNext()) {
                bh.consume(rs.next());
            }
        }
    }

    /** Measures performance of an optimizer's decision about what to use for particular query. */
    @Benchmark
    public void optimizatorChoiceScan(OptimizatorChoiceState state, Blackhole bh) {
        try (var rs = sql.execute(null, state.query, startDate, limit)) {
            while (rs.hasNext()) {
                bh.consume(rs.next());
            }
        }
    }

    /**
     * Benchmark's entry point.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*" + SqlIndexScanBenchmark.class.getSimpleName() + ".*")
                .build();

        new Runner(opt).run();
    }

    /**
     * State that keep query that prevent sql engine to use indexes.
     */
    @State(Scope.Benchmark)
    public static class ForceTableScanState {
        private String query;

        @Setup
        public void setup() {
            query = format(SELECT_STATEMENT_TEMPLATE, "/*+ NO_INDEX */");
        }
    }

    /**
     * State that keep query that forces sql engine to use index.
     */
    @State(Scope.Benchmark)
    public static class ForceIndexScanState {
        private String query;

        @Setup
        public void setup() {
            query = format(SELECT_STATEMENT_TEMPLATE, "/*+ FORCE_INDEX(test_val_idx) */");
        }
    }

    /**
     * State that keep query with any hints about which access method to prefer.
     */
    @State(Scope.Benchmark)
    public static class OptimizatorChoiceState {
        private String query;

        @Setup
        public void setup() {
            query = format(SELECT_STATEMENT_TEMPLATE, "");
        }
    }

    @Override
    protected int nodes() {
        return 1;
    }

    @Override
    protected void createTable(String tableName) {
        // NO-OP
    }

    /**
     * Enumerates scanning modes used in benchmarks.
     */
    public enum ScanMode {
        /**
         * In this mode we will scan first N rows in the order they are appear.
         */
        FIRST_N {
            @Override
            LocalDate valueForPredicate(int limit) {
                return INITIAL_DATE;
            }
        },
        /**
         * In this mode we will scan last N rows in ascending order.
         *
         * <p>For table scan this basically means scanning of the whole table. For index scan
         * this means positioning to the end of the index and scanning last N rows.
         */
        LAST_N {
            @Override
            LocalDate valueForPredicate(int limit) {
                return INITIAL_DATE.plusDays(TABLE_SIZE - limit);
            }
        };

        abstract LocalDate valueForPredicate(int limit);
    }
}


