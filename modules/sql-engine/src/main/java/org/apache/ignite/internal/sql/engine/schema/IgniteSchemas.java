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

package org.apache.ignite.internal.sql.engine.schema;

import org.apache.calcite.schema.SchemaPlus;

/**
 * Schema container.
 */
public final class IgniteSchemas {

    private final SchemaPlus rootSchema;

    private final int catalogVersion;

    /**
     * Constructor.
     *
     * @param rootSchema Root schema.
     * @param catalogVersion Catalog version.
     */
    public IgniteSchemas(SchemaPlus rootSchema, int catalogVersion) {
        this.rootSchema = rootSchema;
        this.catalogVersion = catalogVersion;
    }

    /**
     * Returns a {@link SchemaPlus} that contains all catalog schemas.
     *
     * @return Root schema.
     */
    public SchemaPlus root() {
        return rootSchema;
    }

    /**
     * Returns a catalog version this schema container belongs to.
     *
     * @return Catalog version.
     */
    public int catalogVersion() {
        return catalogVersion;
    }
}
