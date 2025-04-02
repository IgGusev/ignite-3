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

package org.apache.ignite.internal.client.table;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static org.apache.ignite.internal.client.proto.ProtocolBitmaskFeature.TX_DIRECT_MAPPING;
import static org.apache.ignite.internal.client.proto.tx.ClientTxUtils.TX_ID_DIRECT;
import static org.apache.ignite.internal.util.CompletableFutures.nullCompletedFuture;
import static org.apache.ignite.internal.util.ExceptionUtils.matchAny;
import static org.apache.ignite.internal.util.ExceptionUtils.sneakyThrow;
import static org.apache.ignite.internal.util.ExceptionUtils.unwrapCause;
import static org.apache.ignite.lang.ErrorGroups.Client.CONNECTION_ERR;
import static org.apache.ignite.lang.ErrorGroups.Common.INTERNAL_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_ALREADY_FINISHED_ERR;
import static org.apache.ignite.lang.ErrorGroups.Transactions.TX_ALREADY_FINISHED_WITH_TIMEOUT_ERR;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.ignite.client.RetryPolicy;
import org.apache.ignite.internal.client.ClientSchemaVersionMismatchException;
import org.apache.ignite.internal.client.ClientUtils;
import org.apache.ignite.internal.client.PartitionMapping;
import org.apache.ignite.internal.client.PayloadInputChannel;
import org.apache.ignite.internal.client.PayloadOutputChannel;
import org.apache.ignite.internal.client.ReliableChannel;
import org.apache.ignite.internal.client.WriteContext;
import org.apache.ignite.internal.client.proto.ClientMessageUnpacker;
import org.apache.ignite.internal.client.proto.ClientOp;
import org.apache.ignite.internal.client.proto.ColumnTypeConverter;
import org.apache.ignite.internal.client.sql.ClientSql;
import org.apache.ignite.internal.client.table.api.PublicApiClientKeyValueView;
import org.apache.ignite.internal.client.table.api.PublicApiClientRecordView;
import org.apache.ignite.internal.client.tx.ClientLazyTransaction;
import org.apache.ignite.internal.client.tx.ClientTransaction;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteBiTuple;
import org.apache.ignite.internal.lang.IgniteTriConsumer;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.marshaller.MarshallersProvider;
import org.apache.ignite.internal.marshaller.UnmappedColumnsException;
import org.apache.ignite.internal.tostring.IgniteToStringBuilder;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.table.KeyValueView;
import org.apache.ignite.table.QualifiedName;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.apache.ignite.table.mapper.Mapper;
import org.apache.ignite.table.partition.PartitionManager;
import org.apache.ignite.tx.Transaction;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Client table API implementation.
 */
public class ClientTable implements Table {
    private final int id;

    // TODO: table name can change, this approach should probably be reworked, see https://issues.apache.org/jira/browse/IGNITE-21237.
    private final QualifiedName name;

    private final ReliableChannel ch;

    private final MarshallersProvider marshallers;

    private final ClientSql sql;

    private final ConcurrentHashMap<Integer, CompletableFuture<ClientSchema>> schemas = new ConcurrentHashMap<>();

    private final IgniteLogger log;

    private static final int UNKNOWN_SCHEMA_VERSION = -1;

    private volatile int latestSchemaVer = UNKNOWN_SCHEMA_VERSION;

    private final Object latestSchemaLock = new Object();

    private final Object partitionAssignmentLock = new Object();

    private volatile PartitionAssignment partitionAssignment = null;

    private volatile int partitionCount = -1;

    private final ClientPartitionManager clientPartitionManager;

    /**
     * Constructor.
     *
     * @param ch Channel.
     * @param marshallers Marshallers provider.
     * @param id Table id.
     * @param name Table name.
     */
    public ClientTable(
            ReliableChannel ch,
            MarshallersProvider marshallers,
            int id,
            QualifiedName name
    ) {
        assert ch != null;
        assert marshallers != null;
        assert name != null;

        this.ch = ch;
        this.marshallers = marshallers;
        this.id = id;
        this.name = name;
        this.log = ClientUtils.logger(ch.configuration(), ClientTable.class);
        this.sql = new ClientSql(ch, marshallers);
        clientPartitionManager = new ClientPartitionManager(this);
    }

    /**
     * Gets the table id.
     *
     * @return Table id.
     */
    public int tableId() {
        return id;
    }

    /**
     * Gets the channel.
     *
     * @return Channel.
     */
    ReliableChannel channel() {
        return ch;
    }

    /** {@inheritDoc} */
    @Override
    public QualifiedName qualifiedName() {
        return name;
    }

    @Override
    public PartitionManager partitionManager() {
        return clientPartitionManager;
    }

    /** {@inheritDoc} */
    @Override
    public <R> RecordView<R> recordView(Mapper<R> recMapper) {
        Objects.requireNonNull(recMapper);

        return new PublicApiClientRecordView<>(new ClientRecordView<>(this, sql, recMapper));
    }

    @Override
    public RecordView<Tuple> recordView() {
        return new PublicApiClientRecordView<>(new ClientRecordBinaryView(this, sql));
    }

    /** {@inheritDoc} */
    @Override
    public <K, V> KeyValueView<K, V> keyValueView(Mapper<K> keyMapper, Mapper<V> valMapper) {
        Objects.requireNonNull(keyMapper);
        Objects.requireNonNull(valMapper);

        return new PublicApiClientKeyValueView<>(new ClientKeyValueView<>(this, sql, keyMapper, valMapper));
    }

    /** {@inheritDoc} */
    @Override
    public KeyValueView<Tuple, Tuple> keyValueView() {
        return new PublicApiClientKeyValueView<>(new ClientKeyValueBinaryView(this, sql));
    }

    CompletableFuture<ClientSchema> getLatestSchema() {
        // latestSchemaVer can be -1 (unknown) or a valid version.
        // In case of unknown version, we request latest from the server and cache it with -1 key
        // to avoid duplicate requests for latest schema.
        return getSchema(latestSchemaVer);
    }

    @TestOnly
    public CompletableFuture<ClientSchema> getSchemaByVersion(int schemaVersion) {
        return getSchema(schemaVersion);
    }

    private CompletableFuture<ClientSchema> getSchema(int ver) {
        CompletableFuture<ClientSchema> fut = schemas.computeIfAbsent(ver, this::loadSchema);

        if (fut.isCompletedExceptionally()) {
            // Do not return failed future. Remove it from the cache and try again.
            schemas.remove(ver, fut);
            fut = schemas.computeIfAbsent(ver, this::loadSchema);
        }

        return fut;
    }

    private CompletableFuture<ClientSchema> loadSchema(int ver) {
        return ch.serviceAsync(ClientOp.SCHEMAS_GET, w -> {
            w.out().packInt(id);

            if (ver == UNKNOWN_SCHEMA_VERSION) {
                w.out().packNil();
            } else {
                w.out().packInt(1);
                w.out().packInt(ver);
            }
        }, r -> {
            ClientMessageUnpacker clientMessageUnpacker = r.in();
            int schemaCnt = clientMessageUnpacker.unpackInt();

            if (schemaCnt == 0) {
                log.warn("Schema not found [tableId=" + id + ", schemaVersion=" + ver + "]");

                throw new IgniteException(INTERNAL_ERR, "Schema not found: " + ver);
            }

            ClientSchema last = null;

            for (var i = 0; i < schemaCnt; i++) {
                last = readSchema(r.in(), ver);

                if (log.isDebugEnabled()) {
                    log.debug("Schema loaded [tableId=" + id + ", schemaVersion=" + last.version() + "]");
                }
            }

            return last;
        });
    }

    private ClientSchema readSchema(ClientMessageUnpacker in, int targetVer) {
        var schemaVer = in.unpackInt();
        var colCnt = in.unpackInt();
        var columns = new ClientColumn[colCnt];
        int valCnt = 0;

        for (int i = 0; i < colCnt; i++) {
            var propCnt = in.unpackInt();

            assert propCnt >= 7;

            var name = in.unpackString();
            var type = ColumnTypeConverter.fromIdOrThrow(in.unpackInt());
            var keyIndex = in.unpackInt();
            var isNullable = in.unpackBoolean();
            var colocationIndex = in.unpackInt();
            var scale = in.unpackInt();
            var precision = in.unpackInt();

            var valIndex = keyIndex < 0 ? valCnt++ : -1;

            // Skip unknown extra properties, if any.
            in.skipValues(propCnt - 7);

            var column = new ClientColumn(name, type, isNullable, keyIndex, valIndex, colocationIndex, i, scale, precision);
            columns[i] = column;
        }

        var schema = new ClientSchema(schemaVer, columns, marshallers);

        if (schemaVer != targetVer) {
            schemas.put(schemaVer, completedFuture(schema));
        }

        synchronized (latestSchemaLock) {
            if (schemaVer > latestSchemaVer) {
                latestSchemaVer = schemaVer;
            }
        }

        return schema;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return IgniteToStringBuilder.toString(ClientTable.class, this);
    }

    /**
     * Writes transaction, if present.
     *
     * @param tx Transaction.
     * @param out Packer.
     * @param ctx Write context.
     */
    public static void writeTx(@Nullable Transaction tx, PayloadOutputChannel out, @Nullable WriteContext ctx) {
        if (tx == null) {
            out.out().packNil();
        } else {
            ClientTransaction tx0 = ClientTransaction.get(tx);

            if (ctx != null && ctx.enlistmentToken != null) {
                out.out().packLong(TX_ID_DIRECT); // For direct enlistment, pass 0 for resourceId to distinguish with proxy mode.
                out.out().packLong(ctx.enlistmentToken);
                out.out().packUuid(tx0.txId());
                out.out().packInt(tx0.commitTableId());
                out.out().packInt(tx0.commitPartition());
                out.out().packUuid(tx0.coordinatorId());
                out.out().packLong(tx0.timeout());
            } else {
                //noinspection resource
                if (tx0.channel() != out.clientChannel()) {
                    // Do not throw IgniteClientConnectionException to avoid retry kicking in.
                    throw new IgniteException(CONNECTION_ERR, "Transaction context has been lost due to connection errors.");
                }

                out.out().packLong(tx0.id());
            }
        }
    }

    /**
     * Performs a schema-based operation.
     *
     * @param opCode Op code.
     * @param writer Writer.
     * @param reader Reader.
     * @param provider Partition awareness provider.
     * @param tx Transaction.
     * @param <T> Result type.
     * @return Future representing pending completion of the operation.
     */
    public <T> CompletableFuture<T> doSchemaOutOpAsync(
            int opCode,
            IgniteTriConsumer<ClientSchema, PayloadOutputChannel, WriteContext> writer,
            Function<PayloadInputChannel, T> reader,
            PartitionAwarenessProvider provider,
            @Nullable Transaction tx) {
        return doSchemaOutInOpAsync(
                opCode,
                writer,
                (schema, unpacker) -> reader.apply(unpacker),
                null,
                false,
                provider,
                null,
                null,
                false,
                tx);
    }

    /**
     * Performs a schema-based operation.
     *
     * @param opCode Op code.
     * @param writer Writer.
     * @param reader Reader.
     * @param provider Partition awareness provider.
     * @param expectNotifications Whether to expect notifications as a result of the operation.
     * @param tx Transaction.
     * @param <T> Result type.
     * @return Future representing pending completion of the operation.
     */
    public <T> CompletableFuture<T> doSchemaOutOpAsync(
            int opCode,
            IgniteTriConsumer<ClientSchema, PayloadOutputChannel, WriteContext> writer,
            Function<PayloadInputChannel, T> reader,
            PartitionAwarenessProvider provider,
            boolean expectNotifications,
            @Nullable Transaction tx) {
        return doSchemaOutInOpAsync(
                opCode,
                writer,
                (schema, unpacker) -> reader.apply(unpacker),
                null,
                false,
                provider,
                null,
                null,
                expectNotifications,
                tx);
    }

    /**
     * Performs a schema-based operation.
     *
     * @param opCode Op code.
     * @param writer Writer.
     * @param reader Reader.
     * @param provider Partition awareness provider.
     * @param retryPolicyOverride Retry policy override.
     * @param tx Transaction.
     * @param <T> Result type.
     * @return Future representing pending completion of the operation.
     */
    <T> CompletableFuture<T> doSchemaOutOpAsync(
            int opCode,
            IgniteTriConsumer<ClientSchema, PayloadOutputChannel, WriteContext> writer,
            Function<PayloadInputChannel, T> reader,
            PartitionAwarenessProvider provider,
            @Nullable RetryPolicy retryPolicyOverride,
            @Nullable Transaction tx) {
        return doSchemaOutInOpAsync(
                opCode,
                writer,
                (schema, unpacker) -> reader.apply(unpacker),
                null,
                false,
                provider,
                retryPolicyOverride,
                null,
                false,
                tx);
    }

    /**
     * Performs a schema-based operation.
     *
     * @param opCode Op code.
     * @param writer Writer.
     * @param reader Reader.
     * @param defaultValue Default value to use when server returns null.
     * @param provider Partition awareness provider.
     * @param tx Transaction.
     * @param <T> Result type.
     * @return Future representing pending completion of the operation.
     */
    <T> CompletableFuture<T> doSchemaOutInOpAsync(
            int opCode,
            IgniteTriConsumer<ClientSchema, PayloadOutputChannel, WriteContext> writer,
            BiFunction<ClientSchema, PayloadInputChannel, T> reader,
            @Nullable T defaultValue,
            PartitionAwarenessProvider provider,
            @Nullable Transaction tx
    ) {
        return doSchemaOutInOpAsync(opCode, writer, reader, defaultValue, true, provider, null, null, false, tx);
    }

    /**
     * Performs a schema-based operation.
     *
     * @param opCode Op code.
     * @param writer Writer.
     * @param reader Reader.
     * @param defaultValue Default value to use when server returns null.
     * @param responseSchemaRequired Whether response schema is required to read the result.
     * @param provider Partition awareness provider.
     * @param retryPolicyOverride Retry policy override.
     * @param schemaVersionOverride Schema version override.
     * @param expectNotifications Whether to expect notifications as a result of the operation.
     * @param tx Transaction.
     * @param <T> Result type.
     * @return Future representing pending completion of the operation.
     */
    private <T> CompletableFuture<T> doSchemaOutInOpAsync(
            int opCode,
            IgniteTriConsumer<ClientSchema, PayloadOutputChannel, WriteContext> writer,
            BiFunction<ClientSchema, PayloadInputChannel, T> reader,
            @Nullable T defaultValue,
            boolean responseSchemaRequired,
            PartitionAwarenessProvider provider,
            @Nullable RetryPolicy retryPolicyOverride,
            @Nullable Integer schemaVersionOverride,
            boolean expectNotifications,
            @Nullable Transaction tx
    ) {
        CompletableFuture<T> fut = new CompletableFuture<>();

        CompletableFuture<ClientSchema> schemaFut = getSchema(schemaVersionOverride == null ? latestSchemaVer : schemaVersionOverride);
        CompletableFuture<List<String>> partitionsFut = getPartitionAssignment();

        // Wait for schema and partition assignment.
        CompletableFuture.allOf(schemaFut, partitionsFut)
                .thenCompose(v -> {
                    ClientSchema schema = schemaFut.getNow(null);

                    PartitionMapping forCrd = getPreferredNodeName(tableId(), provider, partitionsFut.getNow(null), schema, true);

                    return ClientLazyTransaction.ensureStarted(tx, ch, forCrd).thenCompose(tx0 -> {
                        @Nullable PartitionMapping forOp = getPreferredNodeName(tableId(), provider, partitionsFut.getNow(null), schema,
                                tx0 == null); // Force coordinator mode for implicit transactions.

                        WriteContext ctx = new WriteContext();
                        ctx.pm = forOp;

                        return ch.serviceAsync(opCode,
                                        (opCh) -> tx0 == null || tx0.isReadOnly() || forOp == null
                                                || !opCh.protocolContext().isFeatureSupported(TX_DIRECT_MAPPING) ? nullCompletedFuture()
                                                : tx0.enlistFuture(opCh, ctx),
                                        w -> writer.accept(schema, w, ctx),
                                        r -> readSchemaAndReadData(schema, r, reader, defaultValue, responseSchemaRequired, ctx, tx0),
                                        resolvePreferredNode(tx0, forOp),
                                        tx0 == null ? null : tx0.nodeName(),
                                        retryPolicyOverride,
                                        expectNotifications)
                                // Read resulting schema and the rest of the response.
                                .thenCompose(t -> loadSchemaAndReadData(t, reader))
                                .handle((ret, ex) -> {
                                    if (ex != null) {
                                        // In case of direct mapping failure try to roll back the transaction.
                                        if (ctx.enlistmentToken != null && !matchAny(unwrapCause(ex), TX_ALREADY_FINISHED_ERR,
                                                TX_ALREADY_FINISHED_WITH_TIMEOUT_ERR)) {
                                            assert tx0 != null && !tx0.isReadOnly() : "Invalid transaction for direct mapping " + tx;

                                            return tx0.rollbackAsync().handle((ignored, err0) -> {
                                                if (err0 != null) {
                                                    ex.addSuppressed(err0);
                                                }

                                                sneakyThrow(ex);

                                                return (T) null;
                                            });
                                        } else {
                                            sneakyThrow(ex);
                                        }
                                    }

                                    return completedFuture(ret);
                                }).thenCompose(identity());
                    });
                }).whenComplete((res, err) -> {
                    if (err == null) {
                        fut.complete(res);
                        return;
                    }

                    // Retry schema errors, if any.
                    Throwable cause = err;

                    while (cause != null) {
                        if (cause instanceof ClientSchemaVersionMismatchException) {
                            // Retry with specific schema version.
                            int expectedVersion = ((ClientSchemaVersionMismatchException) cause).expectedVersion();

                            doSchemaOutInOpAsync(opCode, writer, reader, defaultValue, responseSchemaRequired, provider,
                                    retryPolicyOverride, expectedVersion, expectNotifications, tx)
                                    .whenComplete((res0, err0) -> {
                                        if (err0 != null) {
                                            fut.completeExceptionally(err0);
                                        } else {
                                            fut.complete(res0);
                                        }
                                    });

                            return;
                        } else if (schemaVersionOverride == null && cause instanceof UnmappedColumnsException) {
                            // Force load latest schema and revalidate user data against it.
                            // When schemaVersionOverride is not null, we already tried to load the schema.
                            schemas.remove(UNKNOWN_SCHEMA_VERSION);

                            doSchemaOutInOpAsync(opCode, writer, reader, defaultValue, responseSchemaRequired, provider,
                                    retryPolicyOverride, UNKNOWN_SCHEMA_VERSION, expectNotifications, tx)
                                    .whenComplete((res0, err0) -> {
                                        if (err0 != null) {
                                            fut.completeExceptionally(err0);
                                        } else {
                                            fut.complete(res0);
                                        }
                                    });

                            return;
                        }

                        cause = cause.getCause();
                    }

                    fut.completeExceptionally(err);
                });

        return fut;
    }

    private static @Nullable String resolvePreferredNode(@Nullable ClientTransaction tx, @Nullable PartitionMapping pm) {
        String opNode = pm == null ? null : pm.nodeConsistentId();

        if (tx != null) {
            return tx.hasCommitPartition() && opNode != null ? opNode : tx.nodeName();
        } else {
            return opNode;
        }
    }

    private <T> @Nullable Object readSchemaAndReadData(
            ClientSchema knownSchema,
            PayloadInputChannel in,
            BiFunction<ClientSchema, PayloadInputChannel, T> fn,
            @Nullable T defaultValue,
            boolean responseSchemaRequired,
            WriteContext ctx,
            @Nullable ClientTransaction tx
    ) {
        // Use enlistment meta only for remote transactions.
        if (ctx.enlistmentToken != null) {
            assert tx != null;
            assert ctx.pm != null;

            String consistentId = in.in().unpackString();
            long token = in.in().unpackLong();

            // Finish enlist on first request only.
            if (ctx.enlistmentToken == 0) {
                tx.tryFinishEnlist(ctx.pm, consistentId, token);
            }
        }

        int schemaVer = in.in().unpackInt();

        if (!responseSchemaRequired) {
            ensureSchemaLoadedAsync(schemaVer);

            return fn.apply(null, in);
        }

        if (in.in().tryUnpackNil()) {
            ensureSchemaLoadedAsync(schemaVer);

            return defaultValue;
        }

        var resSchema = schemaVer == knownSchema.version() ? knownSchema : schemas.get(schemaVer);

        if (resSchema != null) {
            return fn.apply(knownSchema, in);
        }

        // Schema is not yet known - request.
        // Retain unpacker - normally it is closed when this method exits.
        in.in().retain();
        return new IgniteBiTuple<>(in, schemaVer);
    }

    private <T> CompletionStage<T> loadSchemaAndReadData(
            Object data,
            BiFunction<ClientSchema, PayloadInputChannel, T> fn
    ) {
        if (!(data instanceof IgniteBiTuple)) {
            return completedFuture((T) data);
        }

        var biTuple = (IgniteBiTuple<PayloadInputChannel, Integer>) data;

        var in = biTuple.getKey();
        var schemaId = biTuple.getValue();

        assert in != null;
        assert schemaId != null;

        CompletableFuture<T> resFut = getSchema(schemaId).thenApply(schema -> fn.apply(schema, in));

        // Close unpacker.
        resFut.handle((tuple, err) -> {
            in.close();
            return null;
        });

        return resFut;
    }

    private void ensureSchemaLoadedAsync(int schemaVer) {
        if (schemas.get(schemaVer) == null) {
            // The schema is not needed for current response.
            // Load it in the background to keep the client up to date with the latest version.
            getSchema(schemaVer);
        }
    }

    private static boolean isPartitionAssignmentValid(PartitionAssignment pa, long timestamp) {
        return pa != null
                && pa.timestamp >= timestamp
                && !pa.partitionsFut.isCompletedExceptionally();
    }

    /**
     * Gets partition assignment.
     *
     * @return The future.
     */
    public synchronized CompletableFuture<List<String>> getPartitionAssignment() {
        long timestamp = ch.partitionAssignmentTimestamp();
        PartitionAssignment pa = partitionAssignment;

        if (isPartitionAssignmentValid(pa, timestamp)) {
            return pa.partitionsFut;
        }

        synchronized (partitionAssignmentLock) {
            pa = partitionAssignment;
            if (isPartitionAssignmentValid(pa, timestamp)) {
                return pa.partitionsFut;
            }

            // Request assignment, save requested timestamp and future.
            // This way multiple calls to getPartitionAssignment() will return the same future and won't send multiple requests.
            PartitionAssignment newAssignment = new PartitionAssignment();
            newAssignment.timestamp = timestamp;
            newAssignment.partitionsFut = ch.serviceAsync(ClientOp.PARTITION_ASSIGNMENT_GET,
                    w -> {
                        w.out().packInt(id);
                        w.out().packLong(timestamp);
                    },
                    r -> {
                        int cnt = r.in().unpackInt();
                        if (cnt <= 0) {
                            throw new IgniteException(INTERNAL_ERR, "Invalid partition count returned by the server: " + cnt);
                        }

                        int oldPartitionCount = partitionCount;

                        if (oldPartitionCount < 0) {
                            partitionCount = cnt;
                        } else if (oldPartitionCount != cnt) {
                            String message = String.format("Partition count has changed for table '%s': %d -> %d",
                                    name.toCanonicalForm(), oldPartitionCount, cnt);

                            throw new IgniteException(INTERNAL_ERR, message);
                        }

                        boolean assignmentAvailable = r.in().unpackBoolean();
                        if (!assignmentAvailable) {
                            // Invalidate current assignment so that we can retry on the next call.
                            newAssignment.timestamp = HybridTimestamp.NULL_HYBRID_TIMESTAMP;

                            // Return empty array so that per-partition batches can be initialized.
                            // We'll get the actual assignment on the next call.
                            return emptyAssignment(cnt);
                        }

                        // Returned timestamp can be newer than requested.
                        long ts = r.in().unpackLong();
                        assert ts >= timestamp : "Returned timestamp is older than requested: " + ts + " < " + timestamp;

                        newAssignment.timestamp = ts;

                        List<String> res = new ArrayList<>(cnt);
                        for (int i = 0; i < cnt; i++) {
                            res.add(r.in().tryUnpackNil() ? null : r.in().unpackString());
                        }

                        return res;
                    });

            partitionAssignment = newAssignment;

            return newAssignment.partitionsFut;
        }
    }

    /**
     * Gets partition count when available; otherwise, returns -1.
     *
     * @return Partition count, or -1 if not available.
     */
    int tryGetPartitionCount() {
        return partitionCount;
    }

    private static @Nullable PartitionMapping getPreferredNodeName(
            int tableId,
            PartitionAwarenessProvider provider,
            @Nullable List<String> partitions,
            ClientSchema schema,
            boolean coord) {
        assert provider != null;

        if (partitions == null || partitions.isEmpty()) {
            return null;
        }

        Integer partition = provider.partition();

        if (partition != null) {
            String node = partitions.get(partition);
            if (node == null) {
                return null; // Mapping is incomplete.
            }
            return new PartitionMapping(tableId, node, partition);
        }

        Integer hash = provider.getObjectHashCode(schema, coord);
        if (hash == null) {
            return null;
        }

        int part = Math.abs(hash % partitions.size());

        String node = partitions.get(part);
        if (node == null) {
            return null; // Mapping is incomplete.
        }
        return new PartitionMapping(tableId, node, part);
    }

    private static List<String> emptyAssignment(int size) {
        List<String> emptyRes = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            emptyRes.add(null);
        }

        return emptyRes;
    }

    private static class PartitionAssignment {
        volatile long timestamp = HybridTimestamp.NULL_HYBRID_TIMESTAMP;

        CompletableFuture<List<String>> partitionsFut;
    }
}
