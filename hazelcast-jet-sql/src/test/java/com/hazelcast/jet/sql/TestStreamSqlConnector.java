/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql;

import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.EventTimePolicy;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.impl.pipeline.transform.StreamSourceTransform;
import com.hazelcast.jet.pipeline.test.SimpleEvent;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.jet.sql.impl.schema.ExternalField;
import com.hazelcast.jet.sql.impl.schema.JetTable;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.sql.impl.QueryException;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.schema.ConstantTableStatistics;
import com.hazelcast.sql.impl.schema.Table;
import com.hazelcast.sql.impl.schema.TableField;
import com.hazelcast.sql.impl.type.QueryDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.processor.Processors.mapP;
import static com.hazelcast.jet.impl.util.Util.toList;
import static java.util.Collections.singletonList;

public class TestStreamSqlConnector implements SqlConnector {

    public static final String TYPE_NAME = "TestStream";

    private static final List<ExternalField> FIELD_LIST = singletonList(new ExternalField("v", QueryDataType.BIGINT));

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean isStream() {
        return true;
    }

    @NotNull @Override
    public List<ExternalField> resolveAndValidateFields(
            @Nonnull NodeEngine nodeEngine,
            @NotNull Map<String, String> options,
            @NotNull List<ExternalField> userFields
    ) {
        if (userFields.size() > 0) {
            throw QueryException.error("Don't specify external fields, they are fixed");
        }
        return FIELD_LIST;
    }

    @Nonnull @Override
    public Table createTable(
            @Nonnull NodeEngine nodeEngine,
            @Nonnull String schemaName,
            @Nonnull String tableName,
            @Nonnull Map<String, String> options,
            @Nonnull List<ExternalField> externalFields
    ) {
        return new JetTable(
                this,
                toList(externalFields, ef -> new TableField(ef.name(), ef.type(), false)),
                schemaName, tableName, new ConstantTableStatistics(0)
        );
    }

    @Nullable @Override
    public Vertex fullScanReader(
            @Nonnull DAG dag,
            @Nonnull Table table,
            @Nullable String timestampField,
            @Nonnull Expression<Boolean> predicate,
            @Nonnull List<Expression<?>> projection
    ) {
        StreamSourceTransform<SimpleEvent> source = (StreamSourceTransform<SimpleEvent>) TestSources.itemStream(100);
        ProcessorMetaSupplier pms = source.metaSupplierFn.apply(EventTimePolicy.noEventTime());
        Vertex src = dag.newVertex("src", pms);
        Vertex srcMap = dag.newVertex("src-map", mapP((SimpleEvent e) -> new Object[]{e.sequence()}));
        dag.edge(between(src, srcMap).isolated());
        return srcMap;
    }

    @Override
    public boolean supportsFullScanReader() {
        return true;
    }
}
