/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.redis.internal.data.delta;

import static org.apache.geode.redis.internal.data.delta.DeltaType.RETAIN_ELEMENTS_BY_INDEX_RANGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.geode.redis.internal.data.RedisData;
import org.apache.geode.redis.internal.data.RedisList;
import org.apache.geode.test.junit.runners.GeodeParamsRunner;

@RunWith(GeodeParamsRunner.class)
public class RetainElementsByIndexRangeDeltaUnitTest extends AbstractRedisDeltaUnitTest {
  @Test
  @Parameters(method = "getRetainElementsRanges")
  @TestCaseName("{method}: start:{0}, end:{1}, expected:{2}")
  public void testRetainElementsByIndexRangeDelta_forRedisList(int start, int end,
      byte[][] expected)
      throws Exception {
    RedisList redisList = makeRedisList();
    int newVersion = redisList.getVersion() + 1;
    DataInputStream dis = getDataInputStream(start, end, newVersion);
    redisList.fromDelta(dis);

    assertThat(redisList.lrange(0, -1)).containsExactly(expected);
    assertThat(redisList.getVersion()).isEqualTo((byte) newVersion);
  }

  @Test
  @Parameters(method = "getUnsupportedDataTypeInstancesForDelta")
  @TestCaseName("{method}: redisDataType:{0}")
  public void unsupportedDataTypesThrowException(RedisData redisData)
      throws IOException {
    final DataInputStream dis = getDataInputStream(1, 1, 1);

    assertThatThrownBy(() -> redisData.fromDelta(dis)).isInstanceOf(
        IllegalStateException.class)
        .hasMessage("unexpected " + RETAIN_ELEMENTS_BY_INDEX_RANGE);
  }

  private DataInputStream getDataInputStream(int start, int end, int version)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    RetainElementsByIndexRange source =
        new RetainElementsByIndexRange((byte) version, start, end);

    source.serializeTo(dos);

    return new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
  }

  @SuppressWarnings("unused")
  private Object[] getRetainElementsRanges() {
    // Values are start, end, expected result
    // For initial list of {"zero", "one", "two"}
    return new Object[] {
        new Object[] {0, 0, new byte[][] {"zero".getBytes()}},
        new Object[] {0, 1, new byte[][] {"zero".getBytes(), "one".getBytes()}},
        new Object[] {0, 2, new byte[][] {"zero".getBytes(), "one".getBytes(), "two".getBytes()}},
        new Object[] {1, 2, new byte[][] {"one".getBytes(), "two".getBytes()}},
        new Object[] {2, 2, new byte[][] {"two".getBytes()}}
    };
  }

  @SuppressWarnings("unused")
  private Object[] getUnsupportedDataTypeInstancesForDelta() {
    return new Object[] {
        new Object[] {makeRedisHash()},
        new Object[] {makeRedisSet()},
        new Object[] {makeRedisSortedSet()},
        new Object[] {makeRedisString()}
    };
  }
}
