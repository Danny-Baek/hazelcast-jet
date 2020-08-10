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

package com.hazelcast.jet.sql.impl.schema;

import com.hazelcast.jet.sql.SqlConnector;
import com.hazelcast.jet.sql.impl.connector.SqlConnectorCache;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.sql.impl.QueryException;
import com.hazelcast.sql.impl.schema.Table;
import com.hazelcast.sql.impl.schema.TableResolver;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static com.hazelcast.sql.impl.QueryUtils.CATALOG;
import static com.hazelcast.sql.impl.QueryUtils.SCHEMA_NAME_PUBLIC;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class ExternalCatalog implements TableResolver {

    // TODO: is it the best/right name?
    public static final String CATALOG_MAP_NAME = "__sql.catalog";

    private static final List<List<String>> SEARCH_PATHS = singletonList(asList(CATALOG, SCHEMA_NAME_PUBLIC));

    private final NodeEngine nodeEngine;
    private final SqlConnectorCache sqlConnectorCache;

    public ExternalCatalog(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.sqlConnectorCache = new SqlConnectorCache(nodeEngine);
    }

    public boolean createTable(ExternalTable table, boolean replace, boolean ifNotExists) {
        ExternalTable validated = validate(table);

        String name = validated.name();
        if (ifNotExists) {
            return catalogStorage().putIfAbsent(name, validated) == null;
        } else if (replace) {
            catalogStorage().put(name, validated);
        } else if (catalogStorage().putIfAbsent(name, validated) != null) {
            throw QueryException.error("'" + name + "' table already exists");
        }
        return true;
    }

    private ExternalTable validate(ExternalTable table) {
        ExternalTable resolvedTable = table.fields().isEmpty() ? resolveTable(table) : table;

        // catch all the potential errors early - missing connector, class, invalid format or field definitions etc.
        try {
            toTable(resolvedTable);
        } catch (Exception e) {
            throw QueryException.error("Invalid table definition: " + e.getMessage(), e);
        }

        return resolvedTable;
    }

    private ExternalTable resolveTable(ExternalTable table) {
        SqlConnector connector = findConnector(table.type());

        Map<String, String> options = table.options();

        List<ExternalField> fields = connector.resolveFields(nodeEngine, options);
        if (fields.isEmpty()) {
            throw QueryException.error("Empty column list");
        }

        return new ExternalTable(table.name(), table.type(), fields, options);
    }

    public void removeTable(String name, boolean ifExists) {
        if (catalogStorage().remove(name) == null && !ifExists) {
            throw QueryException.error("'" + name + "' table does not exist");
        }
    }

    @Override
    public List<List<String>> getDefaultSearchPaths() {
        return SEARCH_PATHS;
    }

    @Override
    @Nonnull
    public List<Table> getTables() {
        return catalogStorage().values().stream()
                               .map(this::toTable)
                               .collect(toList());
    }

    private Map<String, ExternalTable> catalogStorage() {
        // TODO: use the right storage
        return nodeEngine.getHazelcastInstance().getReplicatedMap(CATALOG_MAP_NAME);
    }

    public Table toTable(ExternalTable table) {
        SqlConnector connector = findConnector(table.type());
        return connector.createTable(nodeEngine, SCHEMA_NAME_PUBLIC, table.name(), table.options(), table.fields());
    }

    private SqlConnector findConnector(String type) {
        return requireNonNull(sqlConnectorCache.forType(type), "Unknown connector type '" + type + "'");
    }

    public void clear() {
        catalogStorage().clear();
    }
}
