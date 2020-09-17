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

package com.hazelcast.jet.sql.impl.extract;

import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.sql.impl.QueryException;
import com.hazelcast.sql.impl.extract.QueryExtractor;
import com.hazelcast.sql.impl.extract.QueryTarget;
import com.hazelcast.sql.impl.type.QueryDataType;
import com.hazelcast.sql.impl.type.QueryDataTypeMismatchException;

import javax.annotation.concurrent.NotThreadSafe;

// remove in favor of IMDG implementation when JSON is supported
@NotThreadSafe
class HazelcastJsonQueryTarget implements QueryTarget {

    private final Extractors extractors;
    private final boolean key;

    private Object target;

    HazelcastJsonQueryTarget(Extractors extractors, boolean key) {
        this.extractors = extractors;
        this.key = key;
    }

    @Override
    public void setTarget(Object target) {
        this.target = target;
    }

    @Override
    public QueryExtractor createExtractor(String path, QueryDataType type) {
        return () -> {
            try {
                Object value = extractors.extract(target, path, null, false);
                return type.convert(value);
            } catch (QueryDataTypeMismatchException e) {
                throw QueryException.dataException("Failed to extract map entry " + (key ? "key" : "value") + " field \""
                        + path + "\" because of type mismatch [expectedClass=" + e.getExpectedClass().getName()
                        + ", actualClass=" + e.getActualClass().getName() + ']').withInvalidate();
            } catch (Exception e) {
                throw QueryException.dataException("Failed to extract map entry " + (key ? "key" : "value") + " field \""
                        + path + "\": " + e.getMessage(), e);
            }
        };
    }
}