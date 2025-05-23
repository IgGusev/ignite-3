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

package org.apache.ignite.internal.sql.engine.tx;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.tx.InternalTransaction;

/**
 * Wrapper for the transaction that encapsulates the management of an implicit/script-driven transaction.
 */
public interface QueryTransactionWrapper {
    /** Unwraps transaction. */
    InternalTransaction unwrap();

    /**
     * Finalizes related transaction as a result of successful execution.
     *
     * <p>Finalization must be called at the end of every transaction-related operation.
     *
     * @return A future representing result of operation.
     */
    CompletableFuture<Void> finalise();

    /**
     * Finalizes related transaction as a result of failed execution.
     *
     * <p>Finalization must be called at the end of every transaction-related operation.
     *
     * @param error An error occurred during operation processing. Must not be null. 
     * @return A future representing result of operation.
     */
    CompletableFuture<Void> finalise(Throwable error);

    /**
     * Returns {@code true} if this transaction was implicitly started by query engine to
     * execute one particular statement, returns {@code false} otherwise.
     *
     * @return {@code true} if transaction was started implicitly by query engine.
     */
    boolean implicit();
}
