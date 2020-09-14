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

package com.hazelcast.jet.sql.impl.connector.test;

import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.impl.pipeline.transform.BatchSourceTransform;
import com.hazelcast.jet.pipeline.BatchSource;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.jet.sql.impl.connector.SqlConnector;
import com.hazelcast.jet.sql.impl.expression.ExpressionUtil;
import com.hazelcast.jet.sql.impl.schema.JetTable;
import com.hazelcast.jet.sql.impl.schema.MappingField;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.sql.SqlService;
import com.hazelcast.sql.impl.QueryException;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.schema.ConstantTableStatistics;
import com.hazelcast.sql.impl.schema.Table;
import com.hazelcast.sql.impl.schema.TableField;
import com.hazelcast.sql.impl.type.QueryDataType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hazelcast.jet.impl.util.Util.toList;
import static java.util.Collections.singletonList;

/**
 * A test batch-data connector. It emits rows with single column named "v"
 * with INT type. It emits {@value #DEFAULT_ITEM_COUNT} items by default,
 * or the number of items can be customized using the {@value
 * #OPTION_ITEM_COUNT} option in DDL. The rows contain the sequence {@code
 * 0 .. itemCount}.
 */
public class TestBatchSqlConnector implements SqlConnector {

    public static final String TYPE_NAME = "TestBatch";
    public static final int DEFAULT_ITEM_COUNT = 10_000;
    public static final String OPTION_ITEM_COUNT = "itemCount";

    private static final List<MappingField> FIELD_LIST = singletonList(new MappingField("v", QueryDataType.INT));
    private static final List<TableField> FIELD_LIST2 = toList(FIELD_LIST, f -> new TableField(f.name(), f.type(), false));

    public static void create(SqlService sqlService, String tableName, int itemCount) {
        sqlService.execute("CREATE MAPPING " + tableName + " TYPE " + TYPE_NAME
                + " OPTIONS (\"itemCount\" '" + itemCount + "')");
    }

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean isStream() {
        return false;
    }

    @Nonnull @Override
    public List<MappingField> resolveAndValidateFields(
            @Nonnull NodeEngine nodeEngine,
            @Nonnull Map<String, String> options,
            @Nonnull List<MappingField> userFields
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
            @Nonnull List<MappingField> resolvedFields
    ) {
        int itemCount = Integer.parseInt(options.getOrDefault(OPTION_ITEM_COUNT, "" + DEFAULT_ITEM_COUNT));
        return new TestBatchTable(this, schemaName, tableName, itemCount);
    }

    @Override
    public boolean supportsFullScanReader() {
        return true;
    }

    @Nonnull @Override
    public Vertex fullScanReader(
            @Nonnull DAG dag,
            @Nonnull Table table,
            @Nullable String timestampField,
            @Nullable Expression<Boolean> predicate,
            @Nonnull List<Expression<?>> projection
    ) {
        int itemCount = ((TestBatchTable) table).itemCount;
        List<Object[]> items = IntStream
                .range(0, itemCount)
                .mapToObj(i -> ExpressionUtil.evaluate(predicate, projection, new Object[] {i}))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        BatchSource<Object[]> source = TestSources.items(items);
        ProcessorMetaSupplier pms = ((BatchSourceTransform<Object[]>) source).metaSupplier;
        return dag.newVertex(table.toString(), pms);
    }

    public static class TestBatchTable extends JetTable {

        private final int itemCount;

        public TestBatchTable(
                @Nonnull SqlConnector sqlConnector,
                @Nonnull String schemaName,
                @Nonnull String name,
                int itemCount
        ) {
            super(sqlConnector, FIELD_LIST2, schemaName, name, new ConstantTableStatistics(itemCount));
            this.itemCount = itemCount;
        }

        @Override
        public String toString() {
            return "TestBatch" + "[" + getSchemaName() + "." + getSqlName() + "])";
        }
    }
}