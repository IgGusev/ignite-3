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

package org.apache.ignite.internal.catalog.descriptors;

import static org.apache.ignite.internal.catalog.storage.serialization.CatalogSerializationUtils.readList;
import static org.apache.ignite.internal.catalog.storage.serialization.CatalogSerializationUtils.writeList;

import java.io.IOException;
import java.util.List;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableSchemaVersions.TableVersion;
import org.apache.ignite.internal.catalog.storage.serialization.CatalogEntrySerializerProvider;
import org.apache.ignite.internal.catalog.storage.serialization.CatalogObjectDataInput;
import org.apache.ignite.internal.catalog.storage.serialization.CatalogObjectDataOutput;
import org.apache.ignite.internal.catalog.storage.serialization.CatalogObjectSerializer;
import org.apache.ignite.internal.catalog.storage.serialization.CatalogSerializer;
import org.apache.ignite.internal.catalog.storage.serialization.MarshallableEntryType;

/**
 * Serializers for {@link TableVersion}.
 */
public class CatalogTableVersionSerializers {
    /**
     * Serializer for {@link TableVersion}.
     */
    @CatalogSerializer(version = 1, since = "3.0.0")
    static class TableVersionSerializerV1 implements CatalogObjectSerializer<TableVersion> {
        private final CatalogEntrySerializerProvider serializers;

        public TableVersionSerializerV1(CatalogEntrySerializerProvider serializers) {
            this.serializers = serializers;
        }

        @Override
        public TableVersion readFrom(CatalogObjectDataInput input) throws IOException {
            CatalogObjectSerializer<CatalogTableColumnDescriptor> serializer =
                    serializers.get(1, MarshallableEntryType.DESCRIPTOR_TABLE_COLUMN.id());

            List<CatalogTableColumnDescriptor> columns = readList(serializer, input);

            return new TableVersion(columns);
        }

        @Override
        public void writeTo(TableVersion tableVersion, CatalogObjectDataOutput output) throws IOException {
            CatalogObjectSerializer<CatalogTableColumnDescriptor> serializer =
                    serializers.get(1, MarshallableEntryType.DESCRIPTOR_TABLE_COLUMN.id());

            writeList(tableVersion.columns(), serializer, output);
        }
    }

    /**
     * Serializer for {@link TableVersion}.
     */
    @CatalogSerializer(version = 2, since = "3.1.0")
    static class TableVersionSerializerV2 implements CatalogObjectSerializer<TableVersion> {

        @Override
        public TableVersion readFrom(CatalogObjectDataInput input) throws IOException {
            List<CatalogTableColumnDescriptor> columns = input.readEntryList(CatalogTableColumnDescriptor.class);

            return new TableVersion(columns);
        }

        @Override
        public void writeTo(TableVersion tableVersion, CatalogObjectDataOutput output) throws IOException {
            output.writeEntryList(tableVersion.columns());
        }
    }
}
