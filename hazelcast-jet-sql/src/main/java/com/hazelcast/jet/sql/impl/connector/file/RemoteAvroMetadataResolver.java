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

package com.hazelcast.jet.sql.impl.connector.file;

import com.hazelcast.function.BiFunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.hadoop.impl.ReadHadoopNewApiP;
import com.hazelcast.jet.hadoop.impl.WriteHadoopNewApiP;
import com.hazelcast.jet.sql.impl.connector.Processors;
import com.hazelcast.jet.sql.impl.connector.RowProjector;
import com.hazelcast.jet.sql.impl.extract.AvroQueryTarget;
import com.hazelcast.jet.sql.impl.inject.AvroUpsertTargetDescriptor;
import com.hazelcast.jet.sql.impl.schema.ExternalField;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.schema.TableField;
import com.hazelcast.sql.impl.type.QueryDataType;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.avro.mapreduce.AvroKeyOutputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;

import java.io.IOException;
import java.util.List;

import static com.hazelcast.jet.hadoop.impl.SerializableConfiguration.asSerializable;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.paths;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.resolveFieldsFromSchema;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.schema;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.toTableFields;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.types;
import static com.hazelcast.jet.sql.impl.connector.file.AvroMetadataResolver.validateFields;

final class RemoteAvroMetadataResolver {

    private RemoteAvroMetadataResolver() {
    }

    static List<ExternalField> resolveFields(
            List<ExternalField> userFields,
            FileOptions options,
            Job job
    ) throws IOException {
        if (!userFields.isEmpty()) {
            validateFields(userFields);
            return userFields;
        } else {
            Schema schema = findAvroSchema(options.path(), job.getConfiguration());
            return resolveFieldsFromSchema(schema);
        }
    }

    private static Schema findAvroSchema(String directory, Configuration configuration) throws IOException {
        Path path = new Path(directory);
        try (FileSystem filesystem = path.getFileSystem(configuration)) {
            // TODO: directory check, recursive ???
            RemoteIterator<LocatedFileStatus> filesIterator = filesystem.listFiles(path, false);
            if (filesIterator.hasNext()) {
                LocatedFileStatus file = filesIterator.next();

                try (
                        DataFileStream<GenericRecord> stream =
                                new DataFileStream<>(filesystem.open(file.getPath()), new GenericDatumReader<>())
                ) {
                    return stream.getSchema();
                }
            }
        }
        throw new IllegalArgumentException("No data found in '" + directory + "'");
    }

    static Metadata resolveMetadata(List<ExternalField> externalFields, FileOptions options, Job job) throws IOException {
        List<TableField> fields = toTableFields(externalFields);
        Schema schema = schema(fields);

        AvroKeyInputFormat.addInputPath(job, new Path(options.path()));
        job.setInputFormatClass(AvroKeyInputFormat.class);

        job.setOutputFormatClass(AvroKeyOutputFormat.class);
        AvroKeyOutputFormat.setOutputPath(job, new Path(options.path()));
        AvroJob.setOutputKeySchema(job, schema);

        return new Metadata(
                new AvroTargetDescriptor(schema, job.getConfiguration()),
                fields
        );
    }

    private static final class AvroTargetDescriptor implements TargetDescriptor {

        private final Schema schema;
        private final Configuration configuration;

        private AvroTargetDescriptor(
                Schema schema,
                Configuration configuration
        ) {
            this.schema = schema;
            this.configuration = configuration;
        }

        @Override
        public ProcessorMetaSupplier readProcessor(
                List<TableField> fields,
                Expression<Boolean> predicate,
                List<Expression<?>> projection
        ) {
            String[] paths = paths(fields);
            QueryDataType[] types = types(fields);

            SupplierEx<RowProjector> projectorSupplier =
                    () -> new RowProjector(new AvroQueryTarget(), paths, types, predicate, projection);

            SupplierEx<BiFunctionEx<AvroKey<GenericRecord>, NullWritable, Object[]>> projectionSupplierFn = () -> {
                RowProjector projector = projectorSupplier.get();
                return (key, value) -> projector.project(key.datum());
            };

            return new ReadHadoopNewApiP.MetaSupplier<>(asSerializable(configuration), projectionSupplierFn);
        }

        @Override
        public ProcessorSupplier projectorProcessor(List<TableField> fields) {
            return Processors.projector(new AvroUpsertTargetDescriptor(schema.toString()), paths(fields), types(fields));
        }

        @Override
        public ProcessorMetaSupplier writeProcessor(List<TableField> fields) {
            return new WriteHadoopNewApiP.MetaSupplier<>(asSerializable(configuration), AvroKey::new, row -> null);
        }
    }
}
