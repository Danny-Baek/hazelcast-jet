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

package com.hazelcast.jet.sql.impl;

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.sql.SqlService;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static com.hazelcast.jet.config.ProcessingGuarantee.EXACTLY_ONCE;
import static com.hazelcast.jet.core.JobStatus.RUNNING;
import static com.hazelcast.jet.sql.SqlTestSupport.javaSerializableMapDdl;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CreateJobTest extends SimpleTestInClusterSupport {

    private static SqlService sqlService;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @BeforeClass
    public static void beforeClass() {
        initialize(1, null);
        sqlService = instance().getHazelcastInstance().getSql();
    }

    @Test
    public void when_streamingDmlWithoutCreateJob_then_fail() {
        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        exception.expectMessage("You must use CREATE JOB statement for a streaming DML query");
        sqlService.query("INSERT OVERWRITE dest SELECT v, v FROM src");
    }

    @Test
    public void when_ddlStatementWithCreateJob_then_fail() {
        exception.expectMessage("Encountered \"CREATE\" at line 1, column 19");
        sqlService.query("CREATE JOB job AS CREATE EXTERNAL TABLE src TYPE TestStream");
    }

    @Test
    public void when_dqlStatementWithCreateJob_then_fail() {
        exception.expectMessage("Encountered \"SELECT\" at line 1, column 19." + System.lineSeparator() +
                "Was expecting:" + System.lineSeparator() +
                "    \"INSERT\"");
        sqlService.query("CREATE JOB job AS SELECT 42 FROM my_map");
    }

    @Test
    public void testJobSubmitAndCancel() {
        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        sqlService.query("CREATE JOB testJob AS INSERT OVERWRITE dest SELECT v, v FROM src");

        assertNotNull("job doesn't exist", instance().getJob("testJob"));

        sqlService.query("DROP JOB testJob");
    }

    @Test
    public void when_duplicateName_then_fails() {
        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        sqlService.query("CREATE JOB testJob AS INSERT OVERWRITE dest SELECT v, v FROM src");

        assertThatThrownBy(() ->
            sqlService.query("CREATE JOB testJob AS INSERT OVERWRITE dest SELECT v, v FROM src"))
                .hasMessageContaining("Another active job with equal name (testJob) exists");
    }

    @Test
    public void when_duplicateName_and_ifNotExists_then_secondSubmissionIgnored() {
        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        sqlService.query("CREATE JOB testJob AS INSERT OVERWRITE dest SELECT v, v FROM src");
        assertEquals(1, countActiveJobs());

        sqlService.query("CREATE JOB IF NOT EXISTS testJob AS INSERT OVERWRITE dest SELECT v, v FROM src");
        assertEquals(1, countActiveJobs());
    }

    @Test
    public void when_dropNonExistingJob_then_fail() {
        assertThatThrownBy(() ->
            sqlService.query("DROP JOB nonExistingJob"))
                .hasMessageContaining("Job doesn't exist or already terminated");
    }

    @Test
    public void when_dropNonExistingJob_and_ifExists_then_ignore() {
        sqlService.query("DROP JOB IF EXISTS nonExistingJob");
    }

    @Test
    public void test_jobOptions() {
        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        sqlService.query("CREATE JOB testJob " +
                "OPTIONS (" +
                // we use non-default value for each config option
                "processingGuarantee 'exactlyOnce'," +
                "snapshotIntervalMillis '6000'," +
                "autoScaling 'false'," +
                "splitBrainProtectionEnabled 'true'," +
                "metricsEnabled 'false'," +
                "initialSnapshotName 'fooSnapshot')" +
                "AS INSERT OVERWRITE dest SELECT v, v FROM src");

        JobConfig config = instance().getJob("testJob").getConfig();

        assertEquals(EXACTLY_ONCE, config.getProcessingGuarantee());
        assertEquals(6000, config.getSnapshotIntervalMillis());
        assertFalse("isAutoScaling", config.isAutoScaling());
        assertTrue("isSplitBrainProtectionEnabled", config.isSplitBrainProtectionEnabled());
        assertFalse("isMetricsEnabled", config.isMetricsEnabled());
        assertEquals("fooSnapshot", config.getInitialSnapshotName());
    }

    @Test
    public void when_clientDisconnects_then_jobContinues() {
        JetInstance client = factory().newClient();
        SqlService sqlService = client.getHazelcastInstance().getSql();

        sqlService.query("CREATE EXTERNAL TABLE src TYPE TestStream");
        sqlService.query(javaSerializableMapDdl("dest", Long.class, Long.class));

        sqlService.query("CREATE JOB testJob AS INSERT OVERWRITE dest SELECT v, v FROM src");
        Job job = instance().getJob("testJob");
        assertNotNull(job);
        assertJobRunningEventually(client, job, null);

        // When
        client.shutdown();
        sleepSeconds(1);

        // Then
        assertEquals(RUNNING, job.getStatus());
    }

    private long countActiveJobs() {
        return instance().getJobs().stream().filter(j -> !j.getStatus().isTerminal()).count();
    }
}
