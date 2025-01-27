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

package org.apache.geode.redis;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;

public class ClusterNode {
  public final String guid;
  public final String ipAddress;
  public final long port;
  public final boolean primary;
  public final List<Pair<Long, Long>> slots;

  public ClusterNode(String guid, String ipAddress, long port, boolean primary,
      List<Pair<Long, Long>> slots) {
    this.guid = guid;
    this.ipAddress = ipAddress;
    this.port = port;
    this.primary = primary;
    this.slots = slots;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ClusterNode)) {
      return false;
    }
    ClusterNode that = (ClusterNode) o;
    return port == that.port && primary == that.primary && ipAddress.equals(that.ipAddress) &&
        slots.equals(that.slots);
  }

  @Override
  public int hashCode() {
    return Objects.hash(guid, ipAddress, port, primary, slots);
  }

  @Override
  public String toString() {
    return "ClusterNode{" +
        "guid='" + guid + '\'' +
        ", ipAddress='" + ipAddress + '\'' +
        ", port=" + port +
        ", primary=" + primary +
        ", slots=" + slots +
        '}';
  }

  public boolean isSlotOnNode(int slot) {
    for (Pair<Long, Long> slotRange : slots) {
      if (slotRange.getLeft() <= slot && slotRange.getRight() >= slot) {
        return true;
      }
    }
    return false;
  }
}
