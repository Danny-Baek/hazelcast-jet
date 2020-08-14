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

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.sql.SqlQuery;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;
import com.hazelcast.sql.SqlService;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.BitSet;
import java.util.Map;
import java.util.stream.IntStream;

import static com.hazelcast.function.FunctionEx.identity;
import static com.hazelcast.jet.core.JobStatus.FAILED;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class ClientSqlTest extends JetSqlTestSupport {

    @BeforeClass
    public static void setUpClass() {
        // TODO currently fails on 2 members, fix it
        initialize(2, null);
    }

    @Test
    public void test_jetJobReturnRowsToClientFrom() {
        JetInstance client = factory().newClient();
        SqlService sqlService = client.getHazelcastInstance().getSql();
        Map<Integer, Integer> myMap = client.getMap("my_map");
        final int itemCount = 10_000;
        myMap.putAll(IntStream.range(0, itemCount).boxed().collect(toMap(identity(), identity())));

        SqlResult result = sqlService.query("select /*+jet*/ __key, this from my_map");
        BitSet seenValues = new BitSet(itemCount);
        for (SqlRow r : result) {
            Integer v = r.getObject(0);
            assertFalse("value already seen: " + v, seenValues.get(v));
            seenValues.set(v);
        }
        assertEquals(itemCount, seenValues.cardinality());
    }

    @Test
    public void when_clientDisconnects_then_jobCancelled() {
        JetInstance client = factory().newClient();
        SqlService sqlService = client.getHazelcastInstance().getSql();

        sqlService.query("CREATE EXTERNAL TABLE t TYPE " + TestStreamSqlConnector.TYPE_NAME);
        // TODO remove the cursorBufferSize, it's a workaround for client that returns only after a full page
        sqlService.query(new SqlQuery("SELECT * FROM t").setCursorBufferSize(1));

        Job job = instance().getJobs().stream().filter(j -> !j.getStatus().isTerminal()).findFirst().orElse(null);
        assertNotNull("no active job found", job);
        assertJobStatusEventually(job, RUNNING);

        client.shutdown();
        assertJobStatusEventually(job, FAILED);
        assertThatThrownBy(() -> job.join())
                .hasMessageContaining("QueryException: Client cannot be reached");
    }
}
