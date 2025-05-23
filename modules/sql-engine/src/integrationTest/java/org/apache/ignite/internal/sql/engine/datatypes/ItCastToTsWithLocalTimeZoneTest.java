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

package org.apache.ignite.internal.sql.engine.datatypes;

import static org.apache.ignite.internal.lang.IgniteStringFormatter.format;
import static org.apache.ignite.internal.schema.SchemaUtils.TIMESTAMP_MAX;
import static org.apache.ignite.internal.schema.SchemaUtils.TIMESTAMP_MIN;
import static org.apache.ignite.internal.sql.engine.util.SqlTestUtils.assertThrowsSqlException;
import static org.apache.ignite.lang.ErrorGroups.Sql.RUNTIME_ERR;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.ignite.internal.sql.BaseSqlIntegrationTest;
import org.apache.ignite.internal.sql.engine.util.QueryChecker;
import org.apache.ignite.internal.testframework.WithSystemProperty;
import org.apache.ignite.internal.util.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Set of tests to ensure correctness of CAST expression to TIMESTAMP WITH LOCAL TIME ZONE for
 * type pairs supported by cast specification.
 */
@WithSystemProperty(key = "IMPLICIT_PK_ENABLED", value = "true")
public class ItCastToTsWithLocalTimeZoneTest extends BaseSqlIntegrationTest {
    @Override
    protected int initialNodes() {
        return 1;
    }

    @BeforeAll
    static void createTable() {
        CLUSTER.aliveNode().sql().executeScript(
                "CREATE TABLE test (val TIMESTAMP WITH LOCAL TIME ZONE);"
                + "CREATE TABLE src (id INT PRIMARY KEY, s VARCHAR(100), ts TIMESTAMP, d DATE, t TIME)"
        );
    }

    @AfterAll
    static void dropTable() {
        CLUSTER.aliveNode().sql().executeScript(
                "DROP TABLE IF EXISTS test;"
                        + "DROP TABLE IF EXISTS src"
        );
    }

    @AfterEach
    void clearTable() {
        sql("DELETE FROM test");
        sql("DELETE FROM src");
    }

    @ParameterizedTest
    @MethodSource("literalsWithExpectedResult")
    void explicitCastOfLiteralsOnInsert(String literal, int zoneOffset, Object expectedResult) {
        ZoneId zone = ZoneOffset.ofHours(zoneOffset);

        assertQuery(format("INSERT INTO test VALUES (CAST({} as TIMESTAMP WITH LOCAL TIME ZONE))", literal))
                .withTimeZoneId(zone)
                .check();

        assertQuery("SELECT * FROM test")
                .returns(expectedResult)
                .check();
    }

    @ParameterizedTest
    @MethodSource("literalsWithExpectedResult")
    void explicitCastOfLiteralsOnSelect(String literal, int zoneOffset, Object expectedResult) {
        ZoneId zone = ZoneOffset.ofHours(zoneOffset);

        assertQuery(format("SELECT CAST({} as TIMESTAMP WITH LOCAL TIME ZONE)", literal))
                .withTimeZoneId(zone)
                .returns(expectedResult)
                .check();
    }

    @Test
    void explicitCastOfLiteralsOnMultiInsert() {
        List<Object> expectedResults = new ArrayList<>();

        Map<Integer, StringBuilder> statementBuildersByOffset = new HashMap<>();
        for (Arguments args : literalsWithExpectedResult().collect(Collectors.toList())) {
            String literal = (String) args.get()[0];
            int offset = (int) args.get()[1];
            Object expectedResult = args.get()[2];

            StringBuilder builder = statementBuildersByOffset.get(offset);

            if (builder == null) {
                builder = new StringBuilder("INSERT INTO test VALUES");

                statementBuildersByOffset.put(offset, builder);
            } else {
                builder.append(",");
            }

            builder.append("(CAST(").append(literal).append(" as TIMESTAMP WITH LOCAL TIME ZONE))");

            expectedResults.add(expectedResult);
        }

        for (Map.Entry<Integer, StringBuilder> entry : statementBuildersByOffset.entrySet()) {
            assertQuery(entry.getValue().toString())
                    .withTimeZoneId(ZoneOffset.ofHours(entry.getKey()))
                    .check();
        }

        QueryChecker checker = assertQuery("SELECT * FROM test");

        expectedResults.forEach(checker::returns);

        checker.check();
    }

    @ParameterizedTest
    @MethodSource("valuesWithExpectedResult")
    void explicitCastOfDynParamsOnInsert(Object param, int zoneOffset, Object expectedResult) {
        ZoneId zone = ZoneOffset.ofHours(zoneOffset);

        assertQuery("INSERT INTO test VALUES (CAST(? as TIMESTAMP WITH LOCAL TIME ZONE))")
                .withTimeZoneId(zone)
                .withParam(param)
                .check();

        assertQuery("SELECT * FROM test")
                .returns(expectedResult)
                .check();
    }

    @ParameterizedTest
    @MethodSource("valuesWithExpectedResult")
    void explicitCastOfDynParamsOnSelect(Object param, int zoneOffset, Object expectedResult) {
        ZoneId zone = ZoneOffset.ofHours(zoneOffset);

        assertQuery("SELECT CAST(? as TIMESTAMP WITH LOCAL TIME ZONE)")
                .withTimeZoneId(zone)
                .withParam(param)
                .returns(expectedResult)
                .check();
    }

    @Test
    void explicitCastOfDynParamsOnMultiInsert() {
        List<Object> expectedResults = new ArrayList<>();

        Map<Integer, Pair<StringBuilder, List<Object>>> statementBuildersWithParamsByOffset = new HashMap<>();
        for (Arguments args : valuesWithExpectedResult().collect(Collectors.toList())) {
            Object param = args.get()[0];
            int offset = (int) args.get()[1];
            Object expectedResult = args.get()[2];

            Pair<StringBuilder, List<Object>> pair = statementBuildersWithParamsByOffset.get(offset);

            StringBuilder builder;
            List<Object> params;
            if (pair == null) {
                builder = new StringBuilder("INSERT INTO test VALUES");
                params = new ArrayList<>();

                statementBuildersWithParamsByOffset.put(offset, new Pair<>(builder, params));
            } else {
                builder = pair.getFirst();
                params = pair.getSecond();

                builder.append(",");
            }

            builder.append("(CAST(? as TIMESTAMP WITH LOCAL TIME ZONE))");
            params.add(param);

            expectedResults.add(expectedResult);
        }

        for (Map.Entry<Integer, Pair<StringBuilder, List<Object>>> entry : statementBuildersWithParamsByOffset.entrySet()) {
            assertQuery(entry.getValue().getFirst().toString())
                    .withTimeZoneId(ZoneOffset.ofHours(entry.getKey()))
                    .withParams(entry.getValue().getSecond().toArray())
                    .check();
        }

        QueryChecker checker = assertQuery("SELECT * FROM test");

        expectedResults.forEach(checker::returns);

        checker.check();
    }

    @Test
    void explicitCastOfSourceTableOnInsert() {
        sql("INSERT INTO src VALUES "
                + "(1, '1970-01-01 12:00:00', timestamp '1970-01-01 13:00:00', date '1970-01-01', time '12:00:00'),"
                + "(2, NULL, NULL, NULL, NULL)"
        );

        {
            ZoneId zone = ZoneOffset.ofHours(4);

            assertQuery("INSERT INTO test SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(ts as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(d as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(t as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
        }

        {
            ZoneId zone = ZoneOffset.ofHours(8);

            assertQuery("INSERT INTO test SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(ts as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(d as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
            assertQuery("INSERT INTO test SELECT CAST(t as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .check();
        }

        assertQuery("SELECT * FROM test")
                .returns(Instant.parse("1970-01-01T08:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(Instant.parse("1970-01-01T09:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(Instant.parse("1969-12-31T20:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 4))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(Instant.parse("1970-01-01T04:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(Instant.parse("1970-01-01T05:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(Instant.parse("1969-12-31T16:00:00Z"))
                .returns(QueryChecker.NULL_AS_VARARG)
                .returns(localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 8))
                .returns(QueryChecker.NULL_AS_VARARG)
                .check();
    }

    @Test
    void explicitCastOfSourceTableOnSelect() {
        sql("INSERT INTO src VALUES "
                + "(1, '1970-01-01 12:00:00', timestamp '1970-01-01 13:00:00', date '1970-01-01', time '12:00:00'),"
                + "(2, NULL, NULL, NULL, NULL)"
        );

        {
            ZoneId zone = ZoneOffset.ofHours(4);

            assertQuery("SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .returns(Instant.parse("1970-01-01T08:00:00Z"))
                    .check();

            assertQuery("SELECT CAST(ts as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(Instant.parse("1970-01-01T09:00:00Z"))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();

            assertQuery("SELECT CAST(d as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(Instant.parse("1969-12-31T20:00:00Z"))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();

            assertQuery("SELECT CAST(t as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 4))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();
        }

        {
            ZoneId zone = ZoneOffset.ofHours(8);

            assertQuery("SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .returns(Instant.parse("1970-01-01T04:00:00Z"))
                    .check();

            assertQuery("SELECT CAST(ts as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(Instant.parse("1970-01-01T05:00:00Z"))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();

            assertQuery("SELECT CAST(d as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(Instant.parse("1969-12-31T16:00:00Z"))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();

            assertQuery("SELECT CAST(t as TIMESTAMP WITH LOCAL TIME ZONE) FROM src")
                    .withTimeZoneId(zone)
                    .returns(localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 8))
                    .returns(QueryChecker.NULL_AS_VARARG)
                    .check();
        }
    }

    @Test
    public void explicitCastBoundaryDynamicParameterToVarchar() {
        String expression = "SELECT ?::VARCHAR";

        assertQuery(expression)
                .withParam(TIMESTAMP_MAX)
                .withTimeZoneId(ZoneOffset.MAX)
                .returns("9999-12-31 23:59:59.999 GMT+18:00")
                .check();

        expectTsLtzOutOfRangeException(
                () -> assertQuery(expression)
                        .withParam(TIMESTAMP_MAX.plus(1, ChronoUnit.NANOS))
                        .withTimeZoneId(ZoneOffset.MAX)
                        .check()
        );

        assertQuery(expression)
                .withParam(TIMESTAMP_MIN)
                .withTimeZoneId(ZoneOffset.MIN)
                .returns("0001-01-01 00:00:00 GMT-18:00")
                .check();

        expectTsLtzOutOfRangeException(
                () -> assertQuery(expression)
                        .withParam(TIMESTAMP_MIN.minus(1, ChronoUnit.NANOS))
                        .withTimeZoneId(ZoneOffset.MIN)
                        .check()
        );
    }

    @Test
    public void explicitCastBoundaryValues() {
        // Insert source values for check cast.
        {
            String template = "INSERT INTO src "
                    + "(id, s, ts, d) VALUES "
                    + "({}, '{}', timestamp '{}', date '{}')";

            // Maximum allowed values (VARCHAR, TIMESTAMP, DATETIME).
            sql(format(template, 0, "9999-12-30 11:59:59.999999999", "9999-12-30 11:59:59.999999999", "9999-12-30"));
            // Minimum allowed values.
            sql(format(template, 1, "0001-01-02 12:00:00", "0001-01-02 12:00:00", "0001-01-03"));

            // Out of range values for maximum boundary.
            sql(format(template, 2, "9999-12-30 12:00:00", "9999-12-30 12:00:00", "9999-12-31"));

            // Out of range values for minimum boundary.
            // The date '0001-01-02' must throw out of range exception,
            // because '0001-01-01 18:00' (minimum) > ('0001-01-02' - 18 HOURS)
            sql(format(template, 3, "0001-01-02 11:59:59", "0001-01-02 11:59:59", "0001-01-02"));
        }

        // INSERT allowed boundary values.
        {
            String insert = "INSERT INTO test VALUES "
                    // VARCHAR -> TIMESTAMP WITH LOCAL TIME ZONE
                    + "(SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src WHERE id=?),"
                    // TIMESTAMP -> TIMESTAMP WITH LOCAL TIME ZONE (implicit)
                    + "(SELECT ts FROM src WHERE id=?),"
                    // DATE -> TIMESTAMP WITH LOCAL TIME ZONE
                    + "(SELECT CAST(d as TIMESTAMP WITH LOCAL TIME ZONE) FROM src WHERE id=?)";

            assertQuery(insert)
                    .withParams(0, 0, 0)
                    .withTimeZoneId(ZoneOffset.MIN)
                    .check();

            assertQuery(insert)
                    .withParams(1, 1, 1)
                    .withTimeZoneId(ZoneOffset.MAX)
                    .check();

            assertQuery("SELECT val::VARCHAR, val FROM test")
                    .withTimeZoneId(ZoneOffset.MAX)
                    .returns("9999-12-31 23:59:59.999 GMT+18:00", TIMESTAMP_MAX.truncatedTo(ChronoUnit.MILLIS))
                    .returns("9999-12-31 23:59:59.999 GMT+18:00", TIMESTAMP_MAX.truncatedTo(ChronoUnit.MILLIS))
                    .returns("9999-12-31 12:00:00 GMT+18:00", Instant.parse("9999-12-30T18:00:00Z"))

                    .returns("0001-01-02 12:00:00 GMT+18:00", TIMESTAMP_MIN)
                    .returns("0001-01-02 12:00:00 GMT+18:00", TIMESTAMP_MIN)
                    .returns("0001-01-03 00:00:00 GMT+18:00", Instant.parse("0001-01-02T06:00:00Z"))
                    .check();

            assertQuery("SELECT val::VARCHAR, val FROM test")
                    .withTimeZoneId(ZoneOffset.MIN)
                    .returns("9999-12-30 11:59:59.999 GMT-18:00", TIMESTAMP_MAX.truncatedTo(ChronoUnit.MILLIS))
                    .returns("9999-12-30 11:59:59.999 GMT-18:00", TIMESTAMP_MAX.truncatedTo(ChronoUnit.MILLIS))
                    .returns("9999-12-30 00:00:00 GMT-18:00", Instant.parse("9999-12-30T18:00:00Z"))

                    .returns("0001-01-01 00:00:00 GMT-18:00", TIMESTAMP_MIN)
                    .returns("0001-01-01 00:00:00 GMT-18:00", TIMESTAMP_MIN)
                    .returns("0001-01-01 12:00:00 GMT-18:00", Instant.parse("0001-01-02T06:00:00Z"))
                    .check();
        }

        // Overflow for 18 hours (max) timezone.
        {
            List<String> columnNames = List.of("s", "ts", "d");
            String insertTemplate = "INSERT INTO test SELECT CAST({} as TIMESTAMP WITH LOCAL TIME ZONE) FROM src WHERE id=?";

            columnNames.forEach(columnName -> {
                String insert = format(insertTemplate, columnName);

                expectTsLtzOutOfRangeException(
                        () -> assertQuery(insert)
                                .withParam(2)
                                .withTimeZoneId(ZoneOffset.MIN)
                                .check()
                );

                expectTsLtzOutOfRangeException(
                        () -> assertQuery(insert)
                                .withParam(3)
                                .withTimeZoneId(ZoneOffset.MAX)
                                .check()
                );
            });
        }

        sql("DELETE FROM test");

        // No overflow with time zone < 18 hours
        {
            assertQuery("INSERT INTO test SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src WHERE id=2")
                    .withTimeZoneId(ZoneOffset.ofTotalSeconds(ZoneOffset.MIN.getTotalSeconds() + 3600))
                    .check();

            assertQuery("INSERT INTO test SELECT CAST(s as TIMESTAMP WITH LOCAL TIME ZONE) FROM src WHERE id=3")
                    .withTimeZoneId(ZoneOffset.ofTotalSeconds(ZoneOffset.MAX.getTotalSeconds() - 3600))
                    .check();

            assertQuery("SELECT val::VARCHAR, val FROM test")
                    .withTimeZoneId(ZoneOffset.MAX)
                    .returns("9999-12-31 23:00:00 GMT+18:00", TIMESTAMP_MAX.minusSeconds(59 * 60 + 59).truncatedTo(ChronoUnit.SECONDS))
                    .returns("0001-01-02 12:59:59 GMT+18:00", TIMESTAMP_MIN.plusSeconds(59 * 60 + 59))
                    .check();

            assertQuery("SELECT val::VARCHAR, val FROM test")
                    .withTimeZoneId(ZoneOffset.MIN)
                    .returns("9999-12-30 11:00:00 GMT-18:00", TIMESTAMP_MAX.minusSeconds(59 * 60 + 59).truncatedTo(ChronoUnit.SECONDS))
                    .returns("0001-01-01 00:59:59 GMT-18:00", TIMESTAMP_MIN.plusSeconds(59 * 60 + 59))
                    .check();
        }
    }

    private static Stream<Arguments> literalsWithExpectedResult() {
        return Stream.of(
                Arguments.of("NULL", 4, null),
                Arguments.of("NULL", 8, null),
                Arguments.of("'1970-01-01 12:00:00'", 4, Instant.parse("1970-01-01T08:00:00Z")),
                Arguments.of("'1970-01-01 12:00:00'", 8, Instant.parse("1970-01-01T04:00:00Z")),
                Arguments.of("timestamp '1970-01-01 13:00:00'", 4, Instant.parse("1970-01-01T09:00:00Z")),
                Arguments.of("timestamp '1970-01-01 13:00:00'", 8, Instant.parse("1970-01-01T05:00:00Z")),
                Arguments.of("date '1970-01-01'", 4, Instant.parse("1969-12-31T20:00:00Z")),
                Arguments.of("date '1970-01-01'", 8, Instant.parse("1969-12-31T16:00:00Z")),
                Arguments.of("time '12:00:00'", 4, localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 4)),
                Arguments.of("time '12:00:00'", 8, localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 8))
        );
    }

    private static Stream<Arguments> valuesWithExpectedResult() {
        return Stream.of(
                Arguments.of(null, 4, null),
                Arguments.of(null, 8, null),
                Arguments.of("1970-01-01 12:00:00", 4, Instant.parse("1970-01-01T08:00:00Z")),
                Arguments.of("1970-01-01 12:00:00", 8, Instant.parse("1970-01-01T04:00:00Z")),
                Arguments.of(LocalDateTime.parse("1970-01-01T13:00:00"), 4, Instant.parse("1970-01-01T09:00:00Z")),
                Arguments.of(LocalDateTime.parse("1970-01-01T13:00:00"), 8, Instant.parse("1970-01-01T05:00:00Z")),
                Arguments.of(LocalDate.parse("1970-01-01"), 4, Instant.parse("1969-12-31T20:00:00Z")),
                Arguments.of(LocalDate.parse("1970-01-01"), 8, Instant.parse("1969-12-31T16:00:00Z")),
                Arguments.of(LocalTime.parse("12:00:00"), 4, localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 4)),
                Arguments.of(LocalTime.parse("12:00:00"), 8, localDateAndProvidedTimeAtOffset(LocalTime.parse("12:00:00"), 8))
        );
    }

    private static Instant localDateAndProvidedTimeAtOffset(LocalTime time, int offset) {
        ZoneOffset zone = ZoneOffset.ofHours(offset);

        return LocalDateTime.of(LocalDate.now(zone), time).toInstant(zone);
    }

    private static void expectTsLtzOutOfRangeException(Executable exec) {
        assertThrowsSqlException(
                RUNTIME_ERR,
                SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE + " out of range",
                exec
        );
    }
}
