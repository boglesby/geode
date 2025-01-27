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
package org.apache.geode.redis.internal;


import org.apache.logging.log4j.Logger;

import org.apache.geode.cache.Cache;
import org.apache.geode.distributed.internal.ResourceEvent;
import org.apache.geode.distributed.internal.ResourceEventsListener;
import org.apache.geode.internal.cache.CacheService;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.management.ManagementException;
import org.apache.geode.management.internal.beans.CacheServiceMBeanBase;

public class GeodeRedisService implements CacheService, ResourceEventsListener {
  private static final Logger logger = LogService.getLogger();
  private GeodeRedisServer redisServer;
  private InternalCache cache;
  private SystemPropertyBasedRedisConfiguration configuration;

  @Override
  public boolean init(Cache cache) {
    this.cache = (InternalCache) cache;
    configuration = new SystemPropertyBasedRedisConfiguration(
        ((InternalCache) cache).getInternalDistributedSystem().getConfig());
    if (!configuration.isEnabled()) {
      return false;
    }

    this.cache.getInternalDistributedSystem().addResourceListener(this);

    return true;
  }

  @Override
  public void close() {
    stopRedisServer();
  }

  @Override
  public void handleEvent(ResourceEvent event, Object resource) {
    if (event.equals(ResourceEvent.CLUSTER_CONFIGURATION_APPLIED) && resource == cache) {
      startRedisServer(cache);
    }
  }

  private void startRedisServer(InternalCache cache) {
    if (configuration.isEnabled()) {
      try {
        redisServer = new GeodeRedisServer(configuration, cache);
      } catch (IllegalArgumentException ex) {
        throw new ManagementException(ex);
      }
    }
  }

  private void stopRedisServer() {
    if (redisServer != null) {
      redisServer.shutdown();
    }
  }

  @Override
  public Class<? extends CacheService> getInterface() {
    return GeodeRedisService.class;
  }

  @Override
  public CacheServiceMBeanBase getMBean() {
    return null;
  }

  public GeodeRedisServer getRedisServer() {
    return redisServer;
  }

}
