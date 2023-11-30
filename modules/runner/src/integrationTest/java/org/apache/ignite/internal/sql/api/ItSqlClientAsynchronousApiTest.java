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

package org.apache.ignite.internal.sql.api;

import static org.apache.ignite.internal.runner.app.client.ItAbstractThinClientTest.getClientAddresses;

import java.util.List;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.sql.IgniteSql;
import org.apache.ignite.tx.IgniteTransactions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

/**
 * Tests for asynchronous client SQL API.
 */
public class ItSqlClientAsynchronousApiTest extends ItSqlAsynchronousApiTest {
    private IgniteClient client;

    @BeforeAll
    public void startClient() {
        client = IgniteClient.builder().addresses(getClientAddresses(List.of(CLUSTER.aliveNode())).get(0)).build();
    }

    @AfterAll
    public void stopClient() throws Exception {
        client.close();
    }

    @Override
    protected IgniteSql igniteSql() {
        return client.sql();
    }

    @Override
    protected IgniteTransactions igniteTx() {
        return client.transactions();
    }

    @Override
    @Disabled("IGNITE-17134")
    public void closeSession() {
        super.closeSession();
    }

    @Override
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-20598")
    public void checkTransactionsWithDml() {
        super.checkTransactionsWithDml();
    }

    @Override
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-20742")
    public void testLockIsNotReleasedAfterTxRollback() {
        super.testLockIsNotReleasedAfterTxRollback();
    }

    @Override
    public void runScriptThatCompletesSuccessfully() {
        super.runScriptThatCompletesSuccessfully();
    }

    @Override
    public void runScriptThatFails() {
        super.runScriptThatFails();
    }
}
