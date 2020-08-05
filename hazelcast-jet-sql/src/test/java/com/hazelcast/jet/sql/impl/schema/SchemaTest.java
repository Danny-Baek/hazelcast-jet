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

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.JetTestInstanceFactory;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.sql.SqlTestSupport;
import com.hazelcast.jet.sql.impl.connector.map.LocalPartitionedMapConnector;
import com.hazelcast.jet.sql.impl.schema.model.AllTypesValue;
import com.hazelcast.jet.sql.impl.schema.model.IdentifiedPerson;
import com.hazelcast.jet.sql.impl.schema.model.Person;
import com.hazelcast.sql.SqlException;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import static com.hazelcast.jet.sql.SqlConnector.JAVA_SERIALIZATION_FORMAT;
import static com.hazelcast.jet.sql.impl.connector.EntrySqlConnector.TO_KEY_CLASS;
import static com.hazelcast.jet.sql.impl.connector.EntrySqlConnector.TO_SERIALIZATION_KEY_FORMAT;
import static com.hazelcast.jet.sql.impl.connector.EntrySqlConnector.TO_SERIALIZATION_VALUE_FORMAT;
import static com.hazelcast.jet.sql.impl.connector.EntrySqlConnector.TO_VALUE_CLASS;
import static java.lang.String.format;
import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SchemaTest extends SqlTestSupport {

    private static final JetTestInstanceFactory FACTORY = new JetTestInstanceFactory();

    private static JetInstance member;

    @BeforeClass
    public static void beforeClass() {
        member = FACTORY.newMember();
    }

    @AfterClass
    public static void afterClass() {
        FACTORY.shutdownAll();
    }

    @Test
    public void testTableValidation() {
        // given
        String name = "InvalidTable";

        // when
        // then
        assertThatThrownBy(() -> executeQuery(
                member,
                format("CREATE EXTERNAL TABLE %s "
                                + "TYPE \"%s\"",
                        name, LocalPartitionedMapConnector.TYPE_NAME
                ))
        ).isInstanceOf(SqlException.class);
    }

    @Test
    public void testTableCreation() {
        // given
        String name = "DeclaredTable";

        // when
        SqlResult result = executeQuery(
                member,
                format("CREATE EXTERNAL TABLE %s "
                                + "TYPE \"%s\" "
                                + "OPTIONS ("
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s'"
                                + ")",
                        name, LocalPartitionedMapConnector.TYPE_NAME,
                        TO_SERIALIZATION_KEY_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_KEY_CLASS, Integer.class.getName(),
                        TO_SERIALIZATION_VALUE_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_VALUE_CLASS, String.class.getName()
                ));

        // then
        assertTrue(result.isUpdateCount());
        assertEquals(-1, result.updateCount());

        // when
        List<SqlRow> queryRows = getQueryRows(member, format("SELECT __key, this FROM public.%s", name));

        // then
        assertThat(queryRows).isEmpty();
    }

    @Test
    public void testSchemaPropagation() {
        // given
        String name = "DistributedTable";
        JetTestInstanceFactory factory = new JetTestInstanceFactory();
        try {
            JetInstance[] instances = factory.newMembers(new JetConfig(), 2);

            // when create table statement is executed on one member
            executeQuery(
                    instances[0],
                    format("CREATE EXTERNAL TABLE %s "
                                    + "TYPE \"%s\" "
                                    + "OPTIONS ("
                                    + " \"%s\" '%s',"
                                    + " \"%s\" '%s',"
                                    + " \"%s\" '%s',"
                                    + " \"%s\" '%s'"
                                    + ")",
                            name, LocalPartitionedMapConnector.TYPE_NAME,
                            TO_SERIALIZATION_KEY_FORMAT, JAVA_SERIALIZATION_FORMAT,
                            TO_KEY_CLASS, Integer.class.getName(),
                            TO_SERIALIZATION_VALUE_FORMAT, JAVA_SERIALIZATION_FORMAT,
                            TO_VALUE_CLASS, String.class.getName()
                    ));

            // then schema is available on another one
            // TODO: fix it properly - sticky client, different catalog storage ???
            assertTrueEventually(
                    "Table is not available on the second node", () ->
                            assertThatCode(() -> getQueryRows(instances[1], format("SELECT * FROM public.%s", name)))
                                    .doesNotThrowAnyException()
            );
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    public void testDeclaredTablePriority() {
        // given
        String name = "PriorityTable";
        executeQuery(
                member,
                format("CREATE EXTERNAL TABLE %s "
                                + "TYPE \"%s\" "
                                + "OPTIONS ("
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s'"
                                + ")",
                        name, LocalPartitionedMapConnector.TYPE_NAME,
                        TO_SERIALIZATION_KEY_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_KEY_CLASS, Integer.class.getName(),
                        TO_SERIALIZATION_VALUE_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_VALUE_CLASS, Person.class.getName()

                ));

        Map<Integer, Person> map = member.getMap(name);
        map.put(1, new IdentifiedPerson(2, "Alice"));

        // when
        // then
        assertThatThrownBy(() -> executeQuery(member, format("SELECT id FROM %s", name)))
                .isInstanceOf(SqlException.class);
    }

    @Test
    public void testSelectAllSupportedTypes() {
        // given
        String name = "AllFieldsTable";
        executeQuery(member, format("CREATE EXTERNAL TABLE %s "
                        + "TYPE \"%s\" "
                        + "OPTIONS ("
                        + " \"%s\" '%s',"
                        + " \"%s\" '%s',"
                        + " \"%s\" '%s',"
                        + " \"%s\" '%s'"
                        + ")",
                name, LocalPartitionedMapConnector.TYPE_NAME,
                TO_SERIALIZATION_KEY_FORMAT, JAVA_SERIALIZATION_FORMAT,
                TO_KEY_CLASS, BigInteger.class.getName(),
                TO_SERIALIZATION_VALUE_FORMAT, JAVA_SERIALIZATION_FORMAT,
                TO_VALUE_CLASS, AllTypesValue.class.getName()

        ));

        AllTypesValue allTypes = new AllTypesValue(
                "string",
                'a',
                true,
                (byte) 127,
                (short) 32767,
                2147483647,
                9223372036854775807L,
                1234567890.2f,
                123451234567890.2,
                new BigDecimal("9223372036854775.123"),
                new BigInteger("9223372036854775222"),
                LocalTime.of(12, 23, 34),
                LocalDate.of(2020, 4, 15),
                LocalDateTime.of(2020, 4, 15, 12, 23, 34, 100_000_000),
                Date.from(Instant.ofEpochMilli(1586953414200L)),
                GregorianCalendar.from(ZonedDateTime.of(2020, 4, 15, 12, 23, 34, 300_000_000, UTC)),
                Instant.ofEpochMilli(1586953414400L),
                ZonedDateTime.of(2020, 4, 15, 12, 23, 34, 500_000_000, UTC),
                OffsetDateTime.of(2020, 4, 15, 12, 23, 34, 600_000_000, UTC)
        );

        Map<BigInteger, AllTypesValue> map = member.getMap(name);
        map.put(BigInteger.valueOf(13), allTypes);

        // when
        List<SqlRow> rows = getQueryRows(
                member,
                format("SELECT"
                                + " __key"
                                + " ,string"
                                + " ,character0"
                                + " ,boolean0"
                                + " ,byte0"
                                + " ,short0"
                                + " ,int0"
                                + " ,long0"
                                + " ,float0"
                                + " ,double0"
                                + " ,bigDecimal"
                                + " ,bigInteger"
                                + " ,\"localTime\""
                                + " ,localDate"
                                + " ,localDateTime"
                                + " ,\"date\""
                                + " ,calendar"
                                + " ,instant"
                                + " ,zonedDateTime"
                                + " ,offsetDateTime"
                                + " FROM public.%s",
                        name
                )
        );

        // then
        assertThat(rows).hasSize(1);
        assertThat((BigDecimal) rows.get(0).getObject(0)).isEqualTo(new BigDecimal(BigInteger.valueOf(13)));
        assertThat((String) rows.get(0).getObject(1)).isEqualTo(allTypes.getString());
        assertThat((String) rows.get(0).getObject(2)).isEqualTo(Character.toString(allTypes.getCharacter0()));
        assertThat((boolean) rows.get(0).getObject(3)).isEqualTo(allTypes.isBoolean0());
        assertThat((byte) rows.get(0).getObject(4)).isEqualTo(allTypes.getByte0());
        assertThat((short) rows.get(0).getObject(5)).isEqualTo(allTypes.getShort0());
        assertThat((int) rows.get(0).getObject(6)).isEqualTo(allTypes.getInt0());
        assertThat((long) rows.get(0).getObject(7)).isEqualTo(allTypes.getLong0());
        assertThat((float) rows.get(0).getObject(8)).isEqualTo(allTypes.getFloat0());
        assertThat((double) rows.get(0).getObject(9)).isEqualTo(allTypes.getDouble0());
        assertThat((BigDecimal) rows.get(0).getObject(10)).isEqualTo(allTypes.getBigDecimal());
        assertThat((BigDecimal) rows.get(0).getObject(11)).isEqualTo(new BigDecimal(allTypes.getBigInteger()));
        assertThat((LocalTime) rows.get(0).getObject(12)).isEqualTo(allTypes.getLocalTime());
        assertThat((LocalDate) rows.get(0).getObject(13)).isEqualTo(allTypes.getLocalDate());
        assertThat((LocalDateTime) rows.get(0).getObject(14)).isEqualTo(allTypes.getLocalDateTime());

        assertEquals(
            rows.get(0).getObject(15),
            OffsetDateTime.ofInstant(allTypes.getDate().toInstant(), ZoneId.systemDefault())
        );

        assertEquals(
            rows.get(0).getObject(16),
            allTypes.getCalendar().toZonedDateTime().toOffsetDateTime()
        );

        assertEquals(
            rows.get(0).getObject(17),
            OffsetDateTime.ofInstant(allTypes.getInstant(), ZoneId.systemDefault())
        );

        assertEquals(
            rows.get(0).getObject(18),
            allTypes.getZonedDateTime().toOffsetDateTime()
        );

        assertEquals(
            rows.get(0).getObject(19),
            allTypes.offsetDateTime
        );
    }

    @Test
    public void testTableRemoval() {
        // given
        String name = "ToBeDroppedTable";
        executeQuery(
                member,
                format("CREATE EXTERNAL TABLE %s "
                                + "TYPE \"%s\" "
                                + "OPTIONS ("
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s',"
                                + " \"%s\" '%s'"
                                + ")",
                        name, LocalPartitionedMapConnector.TYPE_NAME,
                        TO_SERIALIZATION_KEY_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_KEY_CLASS, Integer.class.getName(),
                        TO_SERIALIZATION_VALUE_FORMAT, JAVA_SERIALIZATION_FORMAT,
                        TO_VALUE_CLASS, Person.class.getName()
                ));

        // when
        executeQuery(member, format("DROP EXTERNAL TABLE %s", name));

        // then
        assertThatThrownBy(() -> executeQuery(member, format("SELECT * FROM public.%s", name))).isInstanceOf(SqlException.class);
    }
}
