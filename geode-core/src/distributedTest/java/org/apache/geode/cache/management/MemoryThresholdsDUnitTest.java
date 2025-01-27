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
package org.apache.geode.cache.management;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.geode.cache.Scope.LOCAL;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.cache.PartitionedRegionHelper.getHashKey;
import static org.apache.geode.internal.cache.control.HeapMemoryMonitor.getTenuredMemoryPoolMXBean;
import static org.apache.geode.internal.cache.control.HeapMemoryMonitor.getTenuredPoolMaxMemory;
import static org.apache.geode.internal.cache.control.HeapMemoryMonitor.getTenuredPoolStatistics;
import static org.apache.geode.test.dunit.Assert.assertEquals;
import static org.apache.geode.test.dunit.Assert.assertFalse;
import static org.apache.geode.test.dunit.Assert.assertNotNull;
import static org.apache.geode.test.dunit.Assert.assertNull;
import static org.apache.geode.test.dunit.Assert.assertTrue;
import static org.apache.geode.test.dunit.Assert.fail;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Ignore;
import org.junit.Test;

import org.apache.geode.DataSerializable;
import org.apache.geode.Statistics;
import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.AttributesMutator;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheLoader;
import org.apache.geode.cache.CacheLoaderException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.LoaderHelper;
import org.apache.geode.cache.LowMemoryException;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolFactory;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.ServerOperationException;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.FunctionAdapter;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.execute.RegionFunctionContext;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache30.CacheSerializableRunnable;
import org.apache.geode.cache30.ClientServerTestCase;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.cache.DistributedRegion;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionHelper;
import org.apache.geode.internal.cache.control.HeapMemoryMonitor;
import org.apache.geode.internal.cache.control.InternalResourceManager;
import org.apache.geode.internal.cache.control.InternalResourceManager.ResourceType;
import org.apache.geode.internal.cache.control.MemoryThresholds.MemoryState;
import org.apache.geode.internal.cache.control.ResourceAdvisor;
import org.apache.geode.internal.cache.control.ResourceListener;
import org.apache.geode.internal.cache.control.TestMemoryThresholdListener;
import org.apache.geode.internal.statistics.GemFireStatSampler;
import org.apache.geode.internal.statistics.LocalStatListener;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.Assert;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.DistributedTestUtils;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.IgnoredException;
import org.apache.geode.test.dunit.Invoke;
import org.apache.geode.test.dunit.LogWriterUtils;
import org.apache.geode.test.dunit.NetworkUtils;
import org.apache.geode.test.dunit.SerializableCallable;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;

/**
 * Tests the Heap Memory thresholds of {@link ResourceManager}
 *
 * @since GemFire 6.0
 */

public class MemoryThresholdsDUnitTest extends ClientServerTestCase {

  public static class Range implements DataSerializable {
    public static final Range DEFAULT = new Range(0, 20);
    public int start;
    public int end;

    public Range() {}

    public Range(int s, int e) {
      start = s;
      end = e;
    }

    public Range(Range r, int shift) {
      start = r.start + shift;
      end = r.end + shift;
    }

    public int width() {
      return end - start;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      out.writeInt(start);
      out.writeInt(end);
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      start = in.readInt();
      end = in.readInt();
    }
  }

  final String expectedEx = "Member: .*? above .*? critical threshold";
  final String addExpectedExString =
      "<ExpectedException action=add>" + expectedEx + "</ExpectedException>";
  final String removeExpectedExString =
      "<ExpectedException action=remove>" + expectedEx + "</ExpectedException>";
  final String expectedBelow = "Member: .*? below .*? critical threshold";
  final String addExpectedBelow =
      "<ExpectedException action=add>" + expectedBelow + "</ExpectedException>";
  final String removeExpectedBelow =
      "<ExpectedException action=remove>" + expectedBelow + "</ExpectedException>";

  final String expectedFunctionEx = " cannot be executed";
  final String addExpectedFunctionExString =
      "<ExpectedException action=add>" + expectedFunctionEx + "</ExpectedException>";
  final String removeExpectedFunctionExString =
      "<ExpectedException action=remove>" + expectedFunctionEx + "</ExpectedException>";

  @Override
  protected final void postSetUpClientServerTestCase() throws Exception {
    Invoke.invokeInEveryVM(setHeapMemoryMonitorTestMode);
    IgnoredException.addIgnoredException(expectedEx);
    IgnoredException.addIgnoredException(expectedBelow);
  }

  @Override
  protected void preTearDownClientServerTestCase() throws Exception {
    Invoke.invokeInEveryVM(resetResourceManager);
  }

  @Test
  public void testPRClientPutRejection() throws Exception {
    doClientServerTest("parRegReject", true/* createPR */);
  }

  @Test
  public void testDistributedRegionClientPutRejection() throws Exception {
    doClientServerTest("distrReject", false/* createPR */);
  }

  private void doClientServerTest(final String regionName, boolean createPR) throws Exception {
    // create region on the server
    final Host host = Host.getHost(0);
    final VM server = host.getVM(0);
    final VM client = host.getVM(1);

    ServerPorts ports = startCacheServer(server, 0f, 90f, regionName, createPR, false, 0);
    startClient(client, server, ports.getPort(), regionName);
    doPuts(client, regionName, false/* catchServerException */, false/* catchLowMemoryException */);
    doPutAlls(client, regionName, false/* catchServerException */,
        false/* catchLowMemoryException */, Range.DEFAULT);

    // make the region sick in the server
    server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
        irm.setCriticalHeapPercentage(90f);

        getCache().getLogger().fine(addExpectedExString);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(950);
        irm.getHeapMonitor().updateStateAndSendEvent();
        getCache().getLogger().fine(removeExpectedExString);
        return null;
      }
    });

    // make sure client puts are rejected
    doPuts(client, regionName, true/* catchServerException */, false/* catchLowMemoryException */);
    doPutAlls(client, regionName, true/* catchServerException */,
        false/* catchLowMemoryException */, new Range(Range.DEFAULT, Range.DEFAULT.width() + 1));
  }

  @Test
  public void testDistributedRegionRemotePutRejectionLocalDestroy() throws Exception {
    doDistributedRegionRemotePutRejection(true, false);
  }

  @Test
  public void testDistributedRegionRemotePutRejectionCacheClose() throws Exception {
    doDistributedRegionRemotePutRejection(false, true);
  }

  @Test
  public void testDistributedRegionRemotePutRejectionBelowThreshold() throws Exception {
    doDistributedRegionRemotePutRejection(false, false);
  }

  /**
   * test that puts in a server are rejected when a remote VM crosses critical threshold
   *
   */
  private void doDistributedRegionRemotePutRejection(boolean localDestroy, boolean cacheClose)
      throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "rejectRemoteOp";

    ServerPorts ports1 = startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 0f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    doPuts(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, Range.DEFAULT);

    // make server2 critical
    setUsageAboveCriticalThreshold(server2);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, false);

    // make sure that local server1 puts are rejected
    doPuts(server1, regionName, false/* catchRejectedException */,
        true/* catchLowMemoryException */);
    Range r1 = new Range(Range.DEFAULT, Range.DEFAULT.width() + 1);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        true/* catchLowMemoryException */, r1);

    if (localDestroy) {
      // local destroy the region on sick member
      server2.invoke(new SerializableCallable("local destroy") {
        @Override
        public Object call() throws Exception {
          Region r = getRootRegion().getSubregion(regionName);
          r.localDestroyRegion();
          return null;
        }
      });
    } else if (cacheClose) {
      server2.invoke(new SerializableCallable() {
        @Override
        public Object call() throws Exception {
          getCache().close();
          return null;
        }
      });
    } else {
      setUsageBelowEviction(server2);
    }

    // wait for remote region destroyed message to be processed
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "remote localRegionDestroyed message not received";
          }

          @Override
          public boolean done() {
            DistributedRegion dr = (DistributedRegion) getRootRegion().getSubregion(regionName);
            return dr.getAtomicThresholdInfo().getMembersThatReachedThreshold().size() == 0;
          }
        };
        GeodeAwaitility.await().untilAsserted(wc);
        return null;
      }
    });

    // make sure puts succeed
    doPuts(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    Range r2 = new Range(r1, r1.width() + 1);
    doPutAlls(server1, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, r2);
  }

  @Test
  public void testBug45513() {
    ResourceManager rm = getCache().getResourceManager();
    assertEquals(0.0f, rm.getCriticalHeapPercentage(), 0);
    assertEquals(0.0f, rm.getEvictionHeapPercentage(), 0);

    rm.setEvictionHeapPercentage(50);
    rm.setCriticalHeapPercentage(90);

    // verify
    assertEquals(50.0f, rm.getEvictionHeapPercentage(), 0);
    assertEquals(90.0f, rm.getCriticalHeapPercentage(), 0);

    getCache().createRegionFactory(RegionShortcut.REPLICATE_HEAP_LRU).create(getName());

    assertEquals(50.0f, rm.getEvictionHeapPercentage(), 0);
    assertEquals(90.0f, rm.getCriticalHeapPercentage(), 0);
  }

  /**
   * test that puts in a client are rejected when a remote VM crosses critical threshold
   *
   */
  @Test
  public void testDistributedRegionRemoteClientPutRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM client = host.getVM(2);

    final String regionName = "rejectRemoteClientOp";

    ServerPorts ports1 = startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 0f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    startClient(client, server1, ports1.getPort(), regionName);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    doPuts(client, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(client, regionName, false/* catchRejectedException */,
        false/* catchLowMemoryException */, Range.DEFAULT);

    // make server2 critical
    setUsageAboveCriticalThreshold(server2);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, false);

    // make sure that client puts are rejected
    doPuts(client, regionName, true/* catchRejectedException */,
        false/* catchLowMemoryException */);
    doPutAlls(client, regionName, true/* catchRejectedException */,
        false/* catchLowMemoryException */, new Range(Range.DEFAULT, Range.DEFAULT.width() + 1));
  }


  /**
   * test that disabling threshold does not cause remote event and remote DISABLED events are
   * delivered
   *
   */
  @Test
  public void testDisabledThresholds() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "disableThresholdPr";

    ServerPorts ports1 = startCacheServer(server1, 0f, 0f, regionName, true/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 0f, 0f, regionName, true/* createPR */,
        false/* notifyBySubscription */, 0);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    setUsageAboveEvictionThreshold(server1);
    verifyListenerValue(server1, MemoryState.EVICTION, 0, false);
    verifyListenerValue(server2, MemoryState.EVICTION, 0, true);

    setThresholds(server1, 80f, 0f);
    verifyListenerValue(server1, MemoryState.EVICTION, 1, false);
    verifyListenerValue(server2, MemoryState.EVICTION, 1, true);

    setUsageAboveCriticalThreshold(server1);
    verifyListenerValue(server1, MemoryState.CRITICAL, 0, false);
    verifyListenerValue(server2, MemoryState.CRITICAL, 0, true);

    setThresholds(server1, 0f, 0f);
    verifyListenerValue(server1, MemoryState.EVICTION_DISABLED, 1, false);
    verifyListenerValue(server2, MemoryState.EVICTION_DISABLED, 1, true);

    setThresholds(server1, 0f, 90f);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, false);
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);

    // verify that stats on server2 are not changed by events on server1
    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        assertEquals(0, irm.getStats().getEvictionStartEvents());
        assertEquals(0, irm.getStats().getHeapCriticalEvents());
        assertEquals(0, irm.getStats().getCriticalThreshold());
        assertEquals(0, irm.getStats().getEvictionThreshold());
        return null;
      }
    });
  }

  /**
   * Make sure appropriate events are delivered when moving between states.
   *
   */
  @Test
  public void testEventDelivery() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);

    final String regionName = "testEventDelivery";

    ServerPorts ports1 = startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    registerLoggingTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    // NORMAL -> CRITICAL
    server2.invoke(new SerializableCallable("NORMAL->CRITICAL") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        getCache().getLogger().fine(addExpectedExString);
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(950, "test");
        getCache().getLogger().fine(removeExpectedExString);
        return null;
      }
    });
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 1, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 0, true);

    // make sure we get two events on remote server
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 1, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 0, true);

    // CRITICAL -> EVICTION
    server2.invoke(new SerializableCallable("CRITICAL->EVICTION") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        getCache().getLogger().fine(addExpectedBelow);
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(850, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 0, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 0, true);

    // EVICTION -> EVICTION
    server2.invoke(new SerializableCallable("EVICTION->EVICTION") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(840, "test");
        return null;
      }
    });
    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 0, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 0, true);

    // EVICTION -> NORMAL
    server2.invoke(new SerializableCallable("EVICTION->NORMAL") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(750, "test");
        return null;
      }
    });

    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 1, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 2, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 1, true);

    // NORMAL -> CRITICAL
    server2.invoke(new SerializableCallable("NORMAL->CRITICAL") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(950, "test");
        return null;
      }
    });

    verifyListenerValue(server2, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 1, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 1, true);

    server2.invoke(new SerializableCallable("CRITICAL->NORMAL") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(750, "test");
        return null;
      }
    });

    verifyListenerValue(server2, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 2, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 3, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 2, true);

    // NORMAL -> EVICTION
    server2.invoke(new SerializableCallable("NORMAL->EVICTION") {
      @Override
      public Object call() throws Exception {
        InternalCache gfCache = getCache();
        gfCache.getInternalResourceManager().getHeapMonitor().updateStateAndSendEvent(850, "test");
        return null;
      }
    });

    verifyListenerValue(server2, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server2, MemoryState.EVICTION, 4, true);
    verifyListenerValue(server2, MemoryState.NORMAL, 2, true);
    verifyListenerValue(server1, MemoryState.CRITICAL, 2, true);
    verifyListenerValue(server1, MemoryState.EVICTION, 4, true);
    verifyListenerValue(server1, MemoryState.NORMAL, 2, true);
  }

  @Test
  public void testCleanAdvisorClose() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM server3 = host.getVM(2);

    final String regionName = "testEventOrger";

    ServerPorts ports1 = startCacheServer(server1, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    verifyProfiles(server1, 2);
    verifyProfiles(server2, 2);

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        closeCache();
        return null;
      }
    });

    verifyProfiles(server1, 1);

    startCacheServer(server3, 0f, 0f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    verifyProfiles(server1, 2);
    verifyProfiles(server3, 2);
  }

  @Test
  public void testPR_RemotePutRejectionLocalDestroy() throws Exception {
    prRemotePutRejection(false, true, false);
  }

  @Test
  public void testPR_RemotePutRejectionCacheClose() throws Exception {
    // Ignore this excetion as this can happen if pool is shutting down
    IgnoredException
        .addIgnoredException(java.util.concurrent.RejectedExecutionException.class.getName());
    prRemotePutRejection(true, false, false);
  }

  @Test
  public void testPR_RemotePutRejection() throws Exception {
    prRemotePutRejection(false, false, false);
  }

  @Test
  public void testPR_RemotePutRejectionLocalDestroyWithTx() throws Exception {
    prRemotePutRejection(false, true, true);
  }

  @Test
  public void testPR_RemotePutRejectionCacheCloseWithTx() throws Exception {
    // Ignore this excetion as this can happen if pool is shutting down
    IgnoredException
        .addIgnoredException(java.util.concurrent.RejectedExecutionException.class.getName());
    prRemotePutRejection(true, false, true);
  }

  @Test
  public void testPR_RemotePutRejectionWithTx() throws Exception {
    prRemotePutRejection(false, false, true);
  }

  private void prRemotePutRejection(boolean cacheClose, boolean localDestroy, final boolean useTx)
      throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM server1 = host.getVM(1);
    final VM server2 = host.getVM(2);
    final VM server3 = host.getVM(3);

    final String regionName = "testPrRejection";
    final int redundancy = 1;

    final ServerPorts ports1 = startCacheServer(server1, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    ServerPorts ports3 = startCacheServer(server3, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getServerProperties());
        getCache();
        AttributesFactory factory = new AttributesFactory();
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(redundancy);
        paf.setLocalMaxMemory(0);
        paf.setTotalNumBuckets(11);
        factory.setPartitionAttributes(paf.create());
        createRegion(regionName, factory.create());
        return null;
      }
    });

    doPuts(accessor, regionName, false, false);
    final Range r1 = Range.DEFAULT;
    doPutAlls(accessor, regionName, false, false, r1);

    SerializableCallable getMyId = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        return getCache().getMyId();
      }
    };

    final DistributedMember server1Id = (DistributedMember) server1.invoke(getMyId);

    setUsageAboveCriticalThreshold(server1);

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        final PartitionedRegion pr = (PartitionedRegion) getRootRegion().getSubregion(regionName);
        final String regionPath = getRootRegion().getSubregion(regionName).getFullPath();
        // server1 is sick, look for a key on server1, and attempt put again
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "remote bucket not marked sick";
          }

          @Override
          public boolean done() {
            boolean keyFoundOnSickMember = false;
            boolean caughtException = false;
            for (int i = 0; i < 20; i++) {
              Integer key = i;
              int hKey = getHashKey(pr, null, key, null, null);
              Set<InternalDistributedMember> owners = pr.getRegionAdvisor().getBucketOwners(hKey);
              if (owners.contains(server1Id)) {
                keyFoundOnSickMember = true;
                try {
                  if (useTx) {
                    getCache().getCacheTransactionManager().begin();
                  }
                  pr.getCache().getLogger().fine("SWAP:putting in tx:" + useTx);
                  pr.put(key, "value");
                  if (useTx) {
                    getCache().getCacheTransactionManager().commit();
                  }
                } catch (LowMemoryException ex) {
                  caughtException = true;
                  if (useTx) {
                    getCache().getCacheTransactionManager().rollback();
                  }
                }
              } else {
                // puts on healthy member should continue
                pr.put(key, "value");
              }
            }
            return keyFoundOnSickMember && caughtException;
          }
        };
        GeodeAwaitility.await().untilAsserted(wc);
        return null;
      }
    });

    {
      Range r2 = new Range(r1, r1.width() + 1);
      doPutAlls(accessor, regionName, false, true, r2);
    }

    if (localDestroy) {
      // local destroy the region on sick member
      server1.invoke(new SerializableCallable("local destroy sick member") {
        @Override
        public Object call() throws Exception {
          Region r = getRootRegion().getSubregion(regionName);
          LogWriterUtils.getLogWriter().info("PRLocalDestroy");
          r.localDestroyRegion();
          return null;
        }
      });
    } else if (cacheClose) {
      // close cache on sick member
      server1.invoke(new SerializableCallable("close cache sick member") {
        @Override
        public Object call() throws Exception {
          getCache().close();
          return null;
        }
      });
    } else {
      setUsageBelowEviction(server1);
    }

    // do put all in a loop to allow distribution of message
    accessor.invoke(new SerializableCallable("Put in a loop") {
      @Override
      public Object call() throws Exception {
        final Region r = getRootRegion().getSubregion(regionName);
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "pr should have gone un-critical";
          }

          @Override
          public boolean done() {
            boolean done = true;
            for (int i = 0; i < 20; i++) {
              try {
                r.put(i, "value");
              } catch (LowMemoryException e) {
                // expected
                done = false;
              }
            }
            return done;
          }
        };
        GeodeAwaitility.await().untilAsserted(wc);
        return null;
      }
    });
    doPutAlls(accessor, regionName, false, false, r1);
  }

  @Ignore("this test is DISABLED due to test issues.  It sometimes fails with a TransactionDataNotColocatedException.  See bug #52222")
  @Test
  public void testTxCommitInCritical() throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM server1 = host.getVM(1);
    final VM server2 = host.getVM(2);
    final VM server3 = host.getVM(3);

    final String regionName = "testPrRejection";
    final int redundancy = 1;

    final ServerPorts ports1 = startCacheServer(server1, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);
    ServerPorts ports3 = startCacheServer(server3, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, redundancy);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);
    registerTestMemoryThresholdListener(server3);

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getServerProperties());
        getCache();
        AttributesFactory factory = new AttributesFactory();
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(redundancy);
        paf.setLocalMaxMemory(0);
        paf.setTotalNumBuckets(11);
        factory.setPartitionAttributes(paf.create());
        createRegion(regionName, factory.create());
        return null;
      }
    });

    doPuts(accessor, regionName, false, false);
    final Range r1 = Range.DEFAULT;
    doPutAlls(accessor, regionName, false, false, r1);

    SerializableCallable getMyId = new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        return getCache().getMyId();
      }
    };

    final DistributedMember server1Id = (DistributedMember) server1.invoke(getMyId);

    final Integer lastKey = (Integer) accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        final PartitionedRegion pr = (PartitionedRegion) getRootRegion().getSubregion(regionName);
        getCache().getCacheTransactionManager().begin();
        for (int i = 0; i < 20; i++) {
          Integer key = i;
          int hKey = PartitionedRegionHelper.getHashKey(pr, key);
          Set<InternalDistributedMember> owners = pr.getRegionAdvisor().getBucketOwners(hKey);
          if (owners.contains(server1Id)) {
            pr.put(key, "txTest");
            return i;
          }
        }
        return null;
      }
    });
    assertNotNull(lastKey);

    setUsageAboveCriticalThreshold(server1);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        final PartitionedRegion pr = (PartitionedRegion) getRootRegion().getSubregion(regionName);
        assertTrue(getCache().getCacheTransactionManager().exists());
        boolean exceptionThrown = false;
        for (int i = lastKey; i < 20; i++) {
          Integer key = i;
          int hKey = PartitionedRegionHelper.getHashKey(pr, key);
          Set<InternalDistributedMember> owners = pr.getRegionAdvisor().getBucketOwners(hKey);
          if (owners.contains(server1Id)) {
            try {
              pr.put(key, "txTest");
            } catch (LowMemoryException e) {
              exceptionThrown = true;
              break;
            }
          }
        }
        if (!exceptionThrown) {
          fail("expected exception not thrown");
        }
        getCache().getCacheTransactionManager().commit();
        int seenCount = 0;
        for (int i = 0; i < 20; i++) {
          if ("txTest".equals(pr.get(i))) {
            seenCount++;
          }
        }
        assertEquals(1, seenCount);
        return null;
      }
    });
  }

  @Test
  public void testDRFunctionExecutionRejection() throws Exception {
    IgnoredException.addIgnoredException("LowMemoryException");
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM client = host.getVM(2);
    final String regionName = "drFuncRej";

    ServerPorts ports1 = startCacheServer(server1, 80f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    startClient(client, server1, ports1.getPort(), regionName);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    final RejectFunction function = new RejectFunction();
    final RejectFunction function2 = new RejectFunction("noRejFunc", false);
    Invoke.invokeInEveryVM(new SerializableCallable("register function") {
      @Override
      public Object call() throws Exception {
        FunctionService.registerFunction(function);
        FunctionService.registerFunction(function2);
        return null;
      }
    });

    doPutAlls(server1, regionName, false, false, Range.DEFAULT);
    doPuts(server1, regionName, false, false);

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });
    // should not fail
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onMembers().execute(function);
        FunctionService.onMembers().execute(function2);
        return null;
      }
    });
    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function)
            .getResult(30, TimeUnit.SECONDS);
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2)
            .getResult(30, TimeUnit.SECONDS);
        return null;
      }
    });

    setUsageAboveCriticalThreshold(server2);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          getCache().getLogger().fine(addExpectedFunctionExString);
          FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
          getCache().getLogger().fine(removeExpectedFunctionExString);
          fail("expected low memory exception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          getCache().getLogger().fine(addExpectedFunctionExString);
          FunctionService.onMembers().execute(function);
          getCache().getLogger().fine(removeExpectedFunctionExString);
          fail("expected low memory exception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        FunctionService.onMembers().execute(function2);
        return null;
      }
    });
    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          getCache().getLogger().fine(addExpectedFunctionExString);
          FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
          getCache().getLogger().fine(removeExpectedFunctionExString);
          fail("expected low memory exception was not thrown");
        } catch (FunctionException e) {
          if (!(e.getCause().getCause() instanceof LowMemoryException)) {
            Assert.fail("unexpected exception ", e);
          }
          // expected
        }
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });
  }

  @Ignore("this test is DISABLED due to intermittent failures.  See bug #52222")
  @Test
  public void testPRFunctionExecutionRejection() throws Exception {
    IgnoredException.addIgnoredException("LowMemoryException");
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(0);
    final VM server1 = host.getVM(1);
    final VM server2 = host.getVM(2);
    final VM client = host.getVM(3);
    final String regionName = "prFuncRej";

    final ServerPorts ports1 = startCacheServer(server1, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, true/* createPR */,
        false/* notifyBySubscription */, 0);
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getServerProperties());
        getCache();
        AttributesFactory factory = new AttributesFactory();
        PartitionAttributesFactory paf = new PartitionAttributesFactory();
        paf.setRedundantCopies(0);
        paf.setLocalMaxMemory(0);
        paf.setTotalNumBuckets(11);
        factory.setPartitionAttributes(paf.create());
        createRegion(regionName, factory.create());
        return null;
      }
    });

    startClient(client, server1, ports1.getPort(), regionName);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    final RejectFunction function = new RejectFunction();
    final RejectFunction function2 = new RejectFunction("noRejFunc", false);
    Invoke.invokeInEveryVM(new SerializableCallable("register function") {
      @Override
      public Object call() throws Exception {
        FunctionService.registerFunction(function);
        FunctionService.registerFunction(function2);
        return null;
      }
    });

    doPutAlls(accessor, regionName, false, false, Range.DEFAULT);
    doPuts(accessor, regionName, false, false);

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });
    // should not fail
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onMembers().execute(function);
        FunctionService.onMembers().execute(function2);
        return null;
      }
    });
    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });

    setUsageAboveCriticalThreshold(server2);

    verifyListenerValue(server1, MemoryState.CRITICAL, 1, true);

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          getCache().getLogger().fine(addExpectedFunctionExString);
          FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
          getCache().getLogger().fine(removeExpectedFunctionExString);
          fail("expected low memory exception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          getCache().getLogger().fine(addExpectedFunctionExString);
          FunctionService.onMembers().execute(function);
          getCache().getLogger().fine(removeExpectedFunctionExString);
          fail("expected low memory exception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        FunctionService.onMembers().execute(function2);
        return null;
      }
    });

    server1.invoke(addExpectedFunctionException);
    server2.invoke(addExpectedFunctionException);

    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function);
          fail("expected low memory exception was not thrown");
        } catch (FunctionException e) {
          if (!(e.getCause().getCause() instanceof LowMemoryException)) {
            Assert.fail("unexpected exception", e);
          }
          // expected
        }
        FunctionService.onRegion(getRootRegion().getSubregion(regionName)).execute(function2);
        return null;
      }
    });

    server1.invoke(removeExpectedFunctionException);
    server2.invoke(removeExpectedFunctionException);

    final DistributedMember server2Id =
        (DistributedMember) server2.invoke(new SerializableCallable() {
          @Override
          public Object call() throws Exception {
            return getCache().getMyId();
          }
        });

    // test function execution on healthy & sick members
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        PartitionedRegion pr = (PartitionedRegion) getRootRegion().getSubregion(regionName);
        Object sickKey1 = null;
        Object sickKey2 = null;
        Object healthyKey = null;
        for (int i = 0; i < 20; i++) {
          Object key = i;
          DistributedMember m = pr.getMemberOwning(key);
          if (m.equals(server2Id)) {
            if (sickKey1 == null) {
              sickKey1 = key;
              // execute
              Set s = new HashSet();
              s.add(sickKey1);
              Execution e = FunctionService.onRegion(pr);
              try {
                getCache().getLogger().fine(addExpectedFunctionExString);
                e.withFilter(s).setArguments(s).execute(function);
                getCache().getLogger().fine(removeExpectedFunctionExString);
                fail("expected LowMemoryExcception was not thrown");
              } catch (LowMemoryException ex) {
                // expected
              }
            } else if (sickKey2 == null) {
              sickKey2 = key;
              // execute
              Set s = new HashSet();
              s.add(sickKey1);
              s.add(sickKey2);
              Execution e = FunctionService.onRegion(pr);
              try {
                e.withFilter(s).setArguments(s).execute(function);
                fail("expected LowMemoryExcception was not thrown");
              } catch (LowMemoryException ex) {
                // expected
              }
            }
          } else {
            healthyKey = key;
            // execute
            Set s = new HashSet();
            s.add(healthyKey);
            Execution e = FunctionService.onRegion(pr);
            e.withFilter(s).setArguments(s).execute(function);
          }
          if (sickKey1 != null && sickKey2 != null && healthyKey != null) {
            break;
          }
        }
        return null;
      }
    });
  }

  @Test
  public void testFunctionExecutionRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM server1 = host.getVM(0);
    final VM server2 = host.getVM(1);
    final VM client = host.getVM(2);
    final String regionName = "FuncRej";

    ServerPorts ports1 = startCacheServer(server1, 80f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);
    ServerPorts ports2 = startCacheServer(server2, 80f, 90f, regionName, false/* createPR */,
        false/* notifyBySubscription */, 0);

    startClient(client, server1, ports1.getPort(), regionName);

    registerTestMemoryThresholdListener(server1);
    registerTestMemoryThresholdListener(server2);

    final RejectFunction function = new RejectFunction();
    final RejectFunction function2 = new RejectFunction("noRejFunc", false);
    Invoke.invokeInEveryVM(new SerializableCallable("register function") {
      @Override
      public Object call() throws Exception {
        FunctionService.registerFunction(function);
        FunctionService.registerFunction(function2);
        return null;
      }
    });

    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Pool p = PoolManager.find("pool1");
        assertTrue(p != null);
        FunctionService.onServers(p).execute(function);
        FunctionService.onServers(p).execute(function2);
        return null;
      }
    });

    final DistributedMember s1 = (DistributedMember) server1.invoke(getDistributedMember);

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        FunctionService.onMembers().execute(function);
        FunctionService.onMember(s1).execute(function);
        FunctionService.onMembers().execute(function2);
        FunctionService.onMember(s1).execute(function2);
        return null;
      }
    });

    setUsageAboveCriticalThreshold(server1);

    verifyListenerValue(server2, MemoryState.CRITICAL, 1, true);

    server1.invoke(addExpectedFunctionException);
    server2.invoke(addExpectedFunctionException);

    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Pool p = PoolManager.find("pool1");
        assertTrue(p != null);
        try {
          FunctionService.onServers(p).execute(function);
          fail("expected LowMemoryExcception was not thrown");
        } catch (ServerOperationException e) {
          if (!(e.getCause().getMessage().matches(".*low.*memory.*"))) {
            Assert.fail("unexpected exception", e);
          }
          // expected
        }
        FunctionService.onServers(p).execute(function2);
        return null;
      }
    });

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        try {
          FunctionService.onMembers().execute(function);
          fail("expected LowMemoryExcception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        try {
          FunctionService.onMember(s1).execute(function);
          fail("expected LowMemoryExcception was not thrown");
        } catch (LowMemoryException e) {
          // expected
        }
        FunctionService.onMembers().execute(function2);
        FunctionService.onMember(s1).execute(function2);
        return null;
      }
    });
    server1.invoke(removeExpectedFunctionException);
    server2.invoke(removeExpectedFunctionException);
  }

  SerializableCallable getDistributedMember = new SerializableCallable() {
    @Override
    public Object call() throws Exception {
      return getCache().getMyId();
    }
  };

  /**
   * Starts up a CacheServer.
   *
   * @return a {@link ServerPorts} containing the CacheServer ports.
   */
  private ServerPorts startCacheServer(VM server, final float evictionThreshold,
      final float criticalThreshold, final String regionName, final boolean createPR,
      final boolean notifyBySubscription, final int prRedundancy) throws Exception {

    return (ServerPorts) server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getServerProperties());
        GemFireCacheImpl cache = (GemFireCacheImpl) getCache();

        InternalResourceManager irm = cache.getInternalResourceManager();
        HeapMemoryMonitor hmm = irm.getHeapMonitor();
        hmm.setTestMaxMemoryBytes(1000);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(500);
        irm.setEvictionHeapPercentage(evictionThreshold);
        irm.setCriticalHeapPercentage(criticalThreshold);

        AttributesFactory factory = new AttributesFactory();
        if (createPR) {
          PartitionAttributesFactory paf = new PartitionAttributesFactory();
          paf.setRedundantCopies(prRedundancy);
          paf.setTotalNumBuckets(11);
          factory.setPartitionAttributes(paf.create());
        } else {
          factory.setScope(Scope.DISTRIBUTED_ACK);
          factory.setDataPolicy(DataPolicy.REPLICATE);
        }
        Region region = createRegion(regionName, factory.create());
        if (createPR) {
          assertTrue(region instanceof PartitionedRegion);
        } else {
          assertTrue(region instanceof DistributedRegion);
        }
        CacheServer cacheServer = getCache().addCacheServer();
        int port = AvailablePortHelper.getRandomAvailableTCPPorts(1)[0];
        cacheServer.setPort(port);
        cacheServer.setNotifyBySubscription(notifyBySubscription);
        cacheServer.start();

        return new ServerPorts(port);
      }
    });
  }

  private void startClient(VM client, final VM server, final int serverPort,
      final String regionName) {

    client.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getSystem(getClientProps());
        getCache();

        PoolFactory pf = PoolManager.createFactory();
        pf.addServer(NetworkUtils.getServerHostName(server.getHost()), serverPort);
        pf.create("pool1");

        AttributesFactory af = new AttributesFactory();
        af.setScope(Scope.LOCAL);
        af.setPoolName("pool1");
        createRegion(regionName, af.create());
        return null;
      }
    });
  }

  private void doPuts(VM vm, final String regionName, final boolean catchServerException,
      final boolean catchLowMemoryException) {

    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getRootRegion().getSubregion(regionName);
        try {
          r.put(0, "value-1");
          if (catchServerException || catchLowMemoryException) {
            fail("An expected ResourceException was not thrown");
          }
        } catch (ServerOperationException ex) {
          if (!catchServerException) {
            Assert.fail("Unexpected exception: ", ex);
          }
          if (!(ex.getCause() instanceof LowMemoryException)) {
            Assert.fail("Unexpected exception: ", ex);
          }
        } catch (LowMemoryException low) {
          if (!catchLowMemoryException) {
            Assert.fail("Unexpected exception: ", low);
          }
        }
        return null;
      }
    });
  }

  private void doPutAlls(VM vm, final String regionName, final boolean catchServerException,
      final boolean catchLowMemoryException, final Range rng) {

    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getRootRegion().getSubregion(regionName);
        Map<Integer, String> temp = new HashMap<>();
        for (int i = rng.start; i < rng.end; i++) {
          Integer k = i;
          temp.put(k, "value-" + i);
        }
        try {
          r.putAll(temp);
          if (catchServerException || catchLowMemoryException) {
            fail("An expected ResourceException was not thrown");
          }
          for (Map.Entry<Integer, String> me : temp.entrySet()) {
            assertEquals(me.getValue(), r.get(me.getKey()));
          }
        } catch (ServerOperationException ex) {
          if (!catchServerException) {
            Assert.fail("Unexpected exception: ", ex);
          }
          if (!(ex.getCause() instanceof LowMemoryException)) {
            Assert.fail("Unexpected exception: ", ex);
          }
          for (Integer me : temp.keySet()) {
            assertFalse("Key " + me + " should not exist", r.containsKey(me));
          }
        } catch (LowMemoryException low) {
          LogWriterUtils.getLogWriter().info("Caught LowMemoryException", low);
          if (!catchLowMemoryException) {
            Assert.fail("Unexpected exception: ", low);
          }
          for (Integer me : temp.keySet()) {
            assertFalse("Key " + me + " should not exist", r.containsKey(me));
          }
        }
        return null;
      }
    });
  }

  private void setUsageAboveCriticalThreshold(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedExString);
        getCache().getInternalResourceManager().getHeapMonitor()
            .updateStateAndSendEvent(950, "test");
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(950);
        getCache().getLogger().fine(removeExpectedExString);
        return null;
      }
    });
  }

  private void setUsageAboveEvictionThreshold(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedBelow);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(850);
        getCache().getInternalResourceManager().getHeapMonitor()
            .updateStateAndSendEvent(850, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
  }

  private void setUsageBelowEviction(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().getLogger().fine(addExpectedBelow);
        getCache().getInternalResourceManager().getHeapMonitor()
            .updateStateAndSendEvent(750, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        return null;
      }
    });
  }

  private void setThresholds(VM server, final float evictionThreshold,
      final float criticalThreshold) {

    server.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        ResourceManager irm = getCache().getResourceManager();
        irm.setCriticalHeapPercentage(criticalThreshold);
        irm.setEvictionHeapPercentage(evictionThreshold);
        return null;
      }
    });
  }

  private void registerTestMemoryThresholdListener(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        TestMemoryThresholdListener listener = new TestMemoryThresholdListener();
        InternalResourceManager irm = getCache().getInternalResourceManager();
        irm.addResourceListener(ResourceType.HEAP_MEMORY, listener);
        assertTrue(irm.getResourceListeners(ResourceType.HEAP_MEMORY).contains(listener));
        return null;
      }
    });
  }

  private void registerLoggingTestMemoryThresholdListener(VM vm) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        TestMemoryThresholdListener listener = new TestMemoryThresholdListener(true);
        InternalResourceManager irm = getCache().getInternalResourceManager();
        irm.addResourceListener(ResourceType.HEAP_MEMORY, listener);
        assertTrue(irm.getResourceListeners(ResourceType.HEAP_MEMORY).contains(listener));
        return null;
      }
    });
  }

  /**
   * Verifies that the test listener value on the given vm is what is expected Note that for remote
   * events useWaitCriterion must be true
   *
   * @param vm the vm where verification should take place
   * @param value the expected value
   * @param useWaitCriterion must be true for remote events
   */
  private void verifyListenerValue(VM vm, final MemoryState state, final int value,
      final boolean useWaitCriterion) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        WaitCriterion wc = null;
        Set<ResourceListener<?>> listeners = getGemfireCache().getInternalResourceManager()
            .getResourceListeners(ResourceType.HEAP_MEMORY);
        TestMemoryThresholdListener tmp_listener = null;
        for (final ResourceListener<?> l : listeners) {
          if (l instanceof TestMemoryThresholdListener) {
            tmp_listener = (TestMemoryThresholdListener) l;
            break;
          }
        }
        final TestMemoryThresholdListener listener = tmp_listener;
        switch (state) {
          case CRITICAL:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote CRITICAL assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getCriticalThresholdCalls();
                }
              };
            } else {
              assertEquals(value, listener.getCriticalThresholdCalls());
            }
            break;
          case CRITICAL_DISABLED:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote CRITICAL_DISABLED assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getCriticalDisabledCalls();
                }
              };
            } else {
              assertEquals(value, listener.getCriticalDisabledCalls());
            }
            break;
          case EVICTION:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote EVICTION assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getEvictionThresholdCalls();
                }
              };
            } else {
              assertEquals(value, listener.getEvictionThresholdCalls());
            }
            break;
          case EVICTION_DISABLED:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote EVICTION_DISABLED assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getEvictionDisabledCalls();
                }
              };
            } else {
              assertEquals(value, listener.getEvictionDisabledCalls());
            }
            break;
          case NORMAL:
            if (useWaitCriterion) {
              wc = new WaitCriterion() {
                @Override
                public String description() {
                  return "Remote NORMAL assert failed " + listener.toString();
                }

                @Override
                public boolean done() {
                  return value == listener.getNormalCalls();
                }
              };
            } else {
              assertEquals(value, listener.getNormalCalls());
            }
            break;
          default:
            throw new IllegalStateException("Unknown memory state");
        }
        if (useWaitCriterion) {
          GeodeAwaitility.await().untilAsserted(wc);
        }
        return null;
      }
    });
  }

  private void verifyProfiles(VM vm, final int numberOfProfiles) {
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        InternalResourceManager irm = getCache().getInternalResourceManager();
        final ResourceAdvisor ra = irm.getResourceAdvisor();
        WaitCriterion wc = new WaitCriterion() {
          @Override
          public String description() {
            return "verify profiles failed. Current profiles: " + ra.adviseGeneric();
          }

          @Override
          public boolean done() {
            return numberOfProfiles == ra.adviseGeneric().size();
          }
        };
        GeodeAwaitility.await().untilAsserted(wc);
        return null;
      }
    });
  }

  protected Properties getClientProps() {
    Properties p = new Properties();
    p.setProperty(MCAST_PORT, "0");
    p.setProperty(LOCATORS, "");
    return p;
  }

  protected Properties getServerProperties() {
    Properties p = new Properties();
    p.setProperty(LOCATORS, "localhost[" + DistributedTestUtils.getDUnitLocatorPort() + "]");
    return p;
  }

  private final SerializableCallable setHeapMemoryMonitorTestMode = new SerializableCallable() {
    @Override
    public Object call() throws Exception {
      HeapMemoryMonitor.setTestDisableMemoryUpdates(true);
      return null;
    }
  };

  private final SerializableCallable resetResourceManager = new SerializableCallable() {
    @Override
    public Object call() throws Exception {
      InternalResourceManager irm = getCache().getInternalResourceManager();
      // Reset CRITICAL_UP by informing all that heap usage is now 1 byte (0 would disable).
      irm.getHeapMonitor().updateStateAndSendEvent(1, "test");
      Set<ResourceListener<?>> listeners = irm.getResourceListeners(ResourceType.HEAP_MEMORY);
      for (final ResourceListener<?> l : listeners) {
        if (l instanceof TestMemoryThresholdListener) {
          ((TestMemoryThresholdListener) l).resetThresholdCalls();
        }
      }
      irm.getHeapMonitor().setTestMaxMemoryBytes(0);
      HeapMemoryMonitor.setTestDisableMemoryUpdates(false);
      return null;
    }
  };

  static class RejectFunction extends FunctionAdapter implements DataSerializable {
    private boolean optimizeForWrite = true;
    private String id = "RejectFunction";

    public RejectFunction() {}

    public RejectFunction(String id, boolean optimizeForWrite) {
      this.id = id;
      this.optimizeForWrite = optimizeForWrite;
    }

    @Override
    public void execute(FunctionContext context) {
      if (context instanceof RegionFunctionContext) {
        RegionFunctionContext regionContext = (RegionFunctionContext) context;
        Region dataSet = regionContext.getDataSet();
        dataSet.get(1);
        regionContext.getResultSender().lastResult("executed");
      } else {
        context.getResultSender().lastResult("executed");
      }
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public boolean hasResult() {
      return true;
    }

    @Override
    public boolean optimizeForWrite() {
      return optimizeForWrite;
    }

    public void setOptimizeForWrite(boolean optimizeForWrite) {
      this.optimizeForWrite = optimizeForWrite;
    }

    @Override
    public boolean isHA() {
      return false;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      out.writeBoolean(optimizeForWrite);
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      optimizeForWrite = in.readBoolean();
    }
  }

  /**
   * putting this test here because junit does not have host stat sampler enabled
   */
  @Test
  public void testLocalStatListenerRegistration() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    Cache cache = getCache();
    InternalDistributedSystem internalSystem =
        (InternalDistributedSystem) cache.getDistributedSystem();
    final GemFireStatSampler sampler = internalSystem.getStatSampler();
    sampler.waitForInitialization(10000); // fix: remove infinite wait
    final LocalStatListener l = value -> latch.countDown();

    // fix: found race condition here...
    WaitCriterion wc = new WaitCriterion() {
      @Override
      public boolean done() {
        Statistics si = getTenuredPoolStatistics(internalSystem.getStatisticsManager());
        if (si != null) {
          sampler.addLocalStatListener(l, si, "currentUsedMemory");
          return true;
        }
        return false;
      }

      @Override
      public String description() {
        String tenuredPoolName = getTenuredMemoryPoolMXBean().getName();
        return "Waiting for " + tenuredPoolName + " statistics to be added to create listener for";
      }
    };
    GeodeAwaitility.await().untilAsserted(wc);

    assertTrue("expected at least one stat listener, found " + sampler.getLocalListeners().size(),
        sampler.getLocalListeners().size() > 0);
    long maxTenuredMemory = getTenuredPoolMaxMemory();
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(LOCAL);
    Region r = createRegion(getUniqueName() + "region", factory.create());
    // keep putting objects (of size 1% of maxTenuredMemory) and wait for stat callback
    // if we don't get a callback after 75 attempts, throw exception
    int count = 0;
    while (true) {
      count++;
      if (count > 75) {
        throw new AssertionError("Did not receive a stat listener callback");
      }
      byte[] value = new byte[(int) (maxTenuredMemory * 0.01)];
      r.put("key-" + count, value);
      if (latch.await(50, MILLISECONDS)) {
        break;
      } else {
        continue;
      }
    }
    r.close();
  }

  /**
   * Test that LocalRegion cache Loads are not stored in the Region if the VM is in a critical
   * state, then test that they are allowed once the VM is no longer critical
   *
   */
  @Test
  public void testLRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM vm = host.getVM(2);
    final String rName = getUniqueName();
    final float criticalHeapThresh = 0.90f;
    final int fakeHeapMaxSize = 1000;

    vm.invoke(JUnit4DistributedTestCase::disconnectFromDS);

    vm.invoke(new CacheSerializableRunnable("test LocalRegion load passthrough when critical") {
      @Override
      public void run2() throws CacheException {
        InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
        HeapMemoryMonitor hmm = irm.getHeapMonitor();
        long fakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.5f)); // below
                                                                                        // critical
                                                                                        // by 50%
        assertTrue(fakeHeapMaxSize > 0);
        irm.getHeapMonitor().setTestMaxMemoryBytes(fakeHeapMaxSize);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(fakeHeapUsage);
        irm.setCriticalHeapPercentage((criticalHeapThresh * 100.0f));
        AttributesFactory<Integer, String> af = new AttributesFactory<>();
        af.setScope(Scope.LOCAL);
        final AtomicInteger numLoaderInvocations = new AtomicInteger();
        af.setCacheLoader(new CacheLoader<Integer, String>() {
          @Override
          public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
            numLoaderInvocations.incrementAndGet();
            return helper.getKey().toString();
          }

          @Override
          public void close() {}
        });
        Region<Integer, String> r = getCache().createRegion(rName, af.create());

        assertFalse(hmm.getState().isCritical());
        int expectedInvocations = 0;
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        {
          Integer k = 1;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(10, 12));
        assertEquals(expectedInvocations++, numLoaderInvocations.get());

        getCache().getLogger().fine(addExpectedExString);
        fakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh + 0.1f)); // usage above
                                                                                   // critical by
                                                                                   // 10%
        assertTrue(fakeHeapUsage > 0);
        assertTrue(fakeHeapUsage <= fakeHeapMaxSize);
        hmm.updateStateAndSendEvent(fakeHeapUsage, "test");
        getCache().getLogger().fine(removeExpectedExString);

        assertTrue(hmm.getState().isCritical());
        {
          Integer k = 2;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(13, 15));
        assertEquals(expectedInvocations++, numLoaderInvocations.get());

        fakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.3f)); // below critical
                                                                                   // by 30%
        assertTrue(fakeHeapMaxSize > 0);
        getCache().getLogger().fine(addExpectedBelow);
        hmm.updateStateAndSendEvent(fakeHeapUsage, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        assertFalse(hmm.getState().isCritical());
        {
          Integer k = 3;
          assertEquals(k.toString(), r.get(k));
        }
        assertEquals(expectedInvocations++, numLoaderInvocations.get());
        expectedInvocations++;
        expectedInvocations++;
        r.getAll(createRanges(16, 18));
        assertEquals(expectedInvocations, numLoaderInvocations.get());

        // Do extra validation that the entry doesn't exist in the local region
        for (Integer i : createRanges(2, 2, 13, 15)) {
          if (r.containsKey(i)) {
            fail("Expected containsKey return false for key" + i);
          }
          if (r.getEntry(i) != null) {
            fail("Expected getEntry to return null for key" + i);
          }
        }
      }
    });
  }

  /**
   * Create a list of integers consisting of the ranges defined by the provided argument e.g..
   * createRanges(1, 4, 10, 12) means create ranges 1 through 4 and 10 through 12 and should yield
   * the list: 1, 2, 3, 4, 10, 11, 12
   */
  public static List<Integer> createRanges(int... startEnds) {
    assert startEnds.length % 2 == 0;
    ArrayList<Integer> ret = new ArrayList<>();
    for (int si = 0; si < startEnds.length; si++) {
      final int start = startEnds[si++];
      final int end = startEnds[si];
      assert end >= start;
      ret.ensureCapacity(ret.size() + ((end - start) + 1));
      for (int i = start; i <= end; i++) {
        ret.add(i);
      }
    }
    return ret;
  }


  /**
   * Test that DistributedRegion cacheLoade and netLoad are passed through to the calling thread if
   * the local VM is in a critical state. Once the VM has moved to a safe state then test that they
   * are allowed.
   *
   */
  @Test
  public void testDRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM replicate1 = host.getVM(2);
    final VM replicate2 = host.getVM(3);
    final String rName = getUniqueName();
    final float criticalHeapThresh = 0.90f;
    final int fakeHeapMaxSize = 1000;

    // Make sure the desired VMs will have a fresh DS.
    AsyncInvocation d1 = replicate1.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    AsyncInvocation d2 = replicate2.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    d1.join();
    assertFalse(d1.exceptionOccurred());
    d2.join();
    assertFalse(d2.exceptionOccurred());
    CacheSerializableRunnable establishConnectivity =
        new CacheSerializableRunnable("establishcConnectivity") {
          @Override
          public void run2() throws CacheException {
            getSystem();
          }
        };
    replicate1.invoke(establishConnectivity);
    replicate2.invoke(establishConnectivity);

    CacheSerializableRunnable createRegion =
        new CacheSerializableRunnable("create DistributedRegion") {
          @Override
          public void run2() throws CacheException {
            // Assert some level of connectivity
            InternalDistributedSystem ds = getSystem();
            assertTrue(ds.getDistributionManager().getNormalDistributionManagerIds().size() >= 1);

            final long fakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.5f)); // below
                                                                                                  // critical
                                                                                                  // by
                                                                                                  // 50%
            InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
            HeapMemoryMonitor hmm = irm.getHeapMonitor();
            assertTrue(fakeHeapMaxSize > 0);
            hmm.setTestMaxMemoryBytes(fakeHeapMaxSize);
            HeapMemoryMonitor.setTestBytesUsedForThresholdSet(fakeHeapUsage);
            irm.setCriticalHeapPercentage((criticalHeapThresh * 100.0f));
            AttributesFactory<Integer, String> af = new AttributesFactory<>();
            af.setScope(Scope.DISTRIBUTED_ACK);
            af.setDataPolicy(DataPolicy.REPLICATE);
            getCache().createRegion(rName, af.create());
          }
        };
    replicate1.invoke(createRegion);
    replicate2.invoke(createRegion);

    replicate1.invoke(addExpectedException);
    replicate2.invoke(addExpectedException);

    final Integer expected =
        (Integer) replicate1.invoke(new SerializableCallable("test Local DistributedRegion Load") {
          @Override
          public Object call() throws Exception {
            Region<Integer, String> r = getCache().getRegion(rName);
            AttributesMutator<Integer, String> am = r.getAttributesMutator();
            am.setCacheLoader(new CacheLoader<Integer, String>() {
              final AtomicInteger numLoaderInvocations = new AtomicInteger();

              @Override
              public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
                Integer expectedInvocations = (Integer) helper.getArgument();
                final int actualInvocations = numLoaderInvocations.getAndIncrement();
                if (expectedInvocations != actualInvocations) {
                  throw new CacheLoaderException("Expected " + expectedInvocations
                      + " invocations, actual is " + actualInvocations);
                }
                return helper.getKey().toString();
              }

              @Override
              public void close() {}
            });

            int expectedInvocations = 0;
            HeapMemoryMonitor hmm =
                ((InternalResourceManager) getCache().getResourceManager()).getHeapMonitor();
            assertFalse(hmm.getState().isCritical());
            {
              Integer k = 1;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }

            long newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh + 0.1f)); // usage
                                                                                               // above
                                                                                               // critical
                                                                                               // by
                                                                                               // 10%
            assertTrue(newfakeHeapUsage > 0);
            assertTrue(newfakeHeapUsage <= fakeHeapMaxSize);
            hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
            assertTrue(hmm.getState().isCritical());
            {
              Integer k = 2;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }

            newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.3f)); // below
                                                                                          // critical
                                                                                          // by 30%
            assertTrue(fakeHeapMaxSize > 0);
            getCache().getLogger().fine(addExpectedBelow);
            hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
            getCache().getLogger().fine(removeExpectedBelow);
            assertFalse(hmm.getState().isCritical());
            {
              Integer k = 3;
              assertEquals(k.toString(), r.get(k, expectedInvocations++));
            }
            return expectedInvocations;
          }
        });

    final CacheSerializableRunnable validateData1 =
        new CacheSerializableRunnable("Validate data 1") {
          @Override
          public void run2() throws CacheException {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer i1 = 1;
            assertTrue(r.containsKey(i1));
            assertNotNull(r.getEntry(i1));
            Integer i2 = 2;
            assertFalse(r.containsKey(i2));
            assertNull(r.getEntry(i2));
            Integer i3 = 3;
            assertTrue(r.containsKey(i3));
            assertNotNull(r.getEntry(i3));
          }
        };
    replicate1.invoke(validateData1);
    replicate2.invoke(validateData1);

    replicate2.invoke(new SerializableCallable("test DistributedRegion netLoad") {
      @Override
      public Object call() throws Exception {
        Region<Integer, String> r = getCache().getRegion(rName);
        HeapMemoryMonitor hmm =
            ((InternalResourceManager) getCache().getResourceManager()).getHeapMonitor();
        assertFalse(hmm.getState().isCritical());

        int expectedInvocations = expected;
        {
          Integer k = 4;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
          assertFalse(hmm.getState().isCritical());
          assertTrue(r.containsKey(k));
        }

        // Place in a critical state for the next test
        long newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh + 0.1f)); // usage
                                                                                           // above
                                                                                           // critical
                                                                                           // by 10%
        assertTrue(newfakeHeapUsage > 0);
        assertTrue(newfakeHeapUsage <= fakeHeapMaxSize);
        hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
        assertTrue(hmm.getState().isCritical());
        {
          Integer k = 5;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
          assertTrue(hmm.getState().isCritical());
          assertFalse(r.containsKey(k));
        }

        newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.3f)); // below
                                                                                      // critical by
                                                                                      // 30%
        assertTrue(fakeHeapMaxSize > 0);
        getCache().getLogger().fine(addExpectedBelow);
        hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        assertFalse(hmm.getState().isCritical());
        {
          Integer k = 6;
          assertEquals(k.toString(), r.get(k, expectedInvocations++));
          assertFalse(hmm.getState().isCritical());
          assertTrue(r.containsKey(k));
        }
        return expectedInvocations;
      }
    });

    replicate1.invoke(removeExpectedException);
    replicate2.invoke(removeExpectedException);

    final CacheSerializableRunnable validateData2 =
        new CacheSerializableRunnable("Validate data 2") {
          @Override
          public void run2() throws CacheException {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer i4 = 4;
            assertTrue(r.containsKey(i4));
            assertNotNull(r.getEntry(i4));
            Integer i5 = 5;
            assertFalse(r.containsKey(i5));
            assertNull(r.getEntry(i5));
            Integer i6 = 6;
            assertTrue(r.containsKey(i6));
            assertNotNull(r.getEntry(i6));
          }
        };
    replicate1.invoke(validateData2);
    replicate2.invoke(validateData2);
  }

  /**
   * Test that a Partitioned Region loader invocation is rejected if the VM with the bucket is in a
   * critical state.
   *
   */
  @Test
  public void testPRLoadRejection() throws Exception {
    final Host host = Host.getHost(0);
    final VM accessor = host.getVM(1);
    final VM ds1 = host.getVM(2);
    final String rName = getUniqueName();
    final float criticalHeapThresh = 0.90f;
    final int fakeHeapMaxSize = 1000;

    // Make sure the desired VMs will have a fresh DS.
    AsyncInvocation d0 = accessor.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    AsyncInvocation d1 = ds1.invokeAsync(JUnit4DistributedTestCase::disconnectFromDS);
    d0.join();
    assertFalse(d0.exceptionOccurred());
    d1.join();
    assertFalse(d1.exceptionOccurred());
    CacheSerializableRunnable establishConnectivity =
        new CacheSerializableRunnable("establishcConnectivity") {
          @Override
          public void run2() throws CacheException {
            getSystem();
          }
        };
    ds1.invoke(establishConnectivity);
    accessor.invoke(establishConnectivity);

    ds1.invoke(createPR(rName, false, fakeHeapMaxSize, criticalHeapThresh));
    accessor.invoke(createPR(rName, true, fakeHeapMaxSize, criticalHeapThresh));

    final AtomicInteger expectedInvocations = new AtomicInteger(0);

    Integer ex = (Integer) accessor
        .invoke(new SerializableCallable("Invoke loader from accessor, non-critical") {
          @Override
          public Object call() throws Exception {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer k = 1;
            Integer expectedInvocations0 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), r.get(k, expectedInvocations0)); // should load for new key
            assertTrue(r.containsKey(k));
            Integer expectedInvocations1 = expectedInvocations.get();
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // no load
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // no load
            return expectedInvocations1;
          }
        });
    expectedInvocations.set(ex);

    ex = (Integer) ds1
        .invoke(new SerializableCallable("Invoke loader from datastore, non-critical") {
          @Override
          public Object call() throws Exception {
            Region<Integer, String> r = getCache().getRegion(rName);
            Integer k = 2;
            Integer expectedInvocations1 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), r.get(k, expectedInvocations1)); // should load for new key
            assertTrue(r.containsKey(k));
            Integer expectedInvocations2 = expectedInvocations.get();
            assertEquals(k.toString(), r.get(k, expectedInvocations2)); // no load
            assertEquals(k.toString(), r.get(k, expectedInvocations2)); // no load
            String oldVal = r.remove(k);
            assertFalse(r.containsKey(k));
            assertEquals(k.toString(), oldVal);
            return expectedInvocations2;
          }
        });
    expectedInvocations.set(ex);

    accessor.invoke(addExpectedException);
    ds1.invoke(addExpectedException);

    ex = (Integer) ds1
        .invoke(new SerializableCallable("Set critical state, assert local load behavior") {
          @Override
          public Object call() throws Exception {
            long newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh + 0.1f)); // usage
                                                                                               // above
                                                                                               // critical
                                                                                               // by
                                                                                               // 10%
            assertTrue(newfakeHeapUsage > 0);
            assertTrue(newfakeHeapUsage <= fakeHeapMaxSize);
            HeapMemoryMonitor hmm =
                ((InternalResourceManager) getCache().getResourceManager()).getHeapMonitor();
            hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
            assertTrue(hmm.getState().isCritical());
            final Integer k = 2; // reload with same key again and again
            final Integer expectedInvocations3 = expectedInvocations.getAndIncrement();
            Region<Integer, String> r = getCache().getRegion(rName);
            assertEquals(k.toString(), r.get(k, expectedInvocations3)); // load
            assertFalse(r.containsKey(k));
            Integer expectedInvocations4 = expectedInvocations.getAndIncrement();
            assertEquals(k.toString(), r.get(k, expectedInvocations4)); // load
            assertFalse(r.containsKey(k));
            Integer expectedInvocations5 = expectedInvocations.get();
            assertEquals(k.toString(), r.get(k, expectedInvocations5)); // load
            assertFalse(r.containsKey(k));
            return expectedInvocations5;
          }
        });
    expectedInvocations.set(ex);

    ex = (Integer) accessor.invoke(new SerializableCallable(
        "During critical state on datastore, assert accesor load behavior") {
      @Override
      public Object call() throws Exception {
        final Integer k = 2; // reload with same key again and again
        Integer expectedInvocations6 = expectedInvocations.incrementAndGet();
        Region<Integer, String> r = getCache().getRegion(rName);
        assertEquals(k.toString(), r.get(k, expectedInvocations6)); // load
        assertFalse(r.containsKey(k));
        Integer expectedInvocations7 = expectedInvocations.incrementAndGet();
        assertEquals(k.toString(), r.get(k, expectedInvocations7)); // load
        assertFalse(r.containsKey(k));
        return expectedInvocations7;
      }
    });
    expectedInvocations.set(ex);

    ex = (Integer) ds1.invoke(
        new SerializableCallable("Set safe state on datastore, assert local load behavior") {
          @Override
          public Object call() throws Exception {
            HeapMemoryMonitor hmm =
                ((InternalResourceManager) getCache().getResourceManager()).getHeapMonitor();
            int newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.3f)); // below
                                                                                              // critical
                                                                                              // by
                                                                                              // 30%
            assertTrue(fakeHeapMaxSize > 0);
            getCache().getLogger().fine(addExpectedBelow);
            hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
            getCache().getLogger().fine(removeExpectedBelow);
            assertFalse(hmm.getState().isCritical());
            Integer k = 3; // same key as previously used, this time is should stick
            Integer expectedInvocations8 = expectedInvocations.incrementAndGet();
            Region<Integer, String> r = getCache().getRegion(rName);
            assertEquals(k.toString(), r.get(k, expectedInvocations8)); // last load for 3
            assertTrue(r.containsKey(k));
            return expectedInvocations8;
          }
        });
    expectedInvocations.set(ex);

    accessor.invoke(new SerializableCallable(
        "Data store in safe state, assert load behavior, accessor sets critical state, assert load behavior") {
      @Override
      public Object call() throws Exception {
        HeapMemoryMonitor hmm =
            ((InternalResourceManager) getCache().getResourceManager()).getHeapMonitor();
        assertFalse(hmm.getState().isCritical());
        Integer k = 4;
        Integer expectedInvocations9 = expectedInvocations.incrementAndGet();
        Region<Integer, String> r = getCache().getRegion(rName);
        assertEquals(k.toString(), r.get(k, expectedInvocations9)); // load for 4
        assertTrue(r.containsKey(k));
        assertEquals(k.toString(), r.get(k, expectedInvocations9)); // no load

        // Go critical in accessor
        getCache().getLogger().fine(addExpectedExString);
        long newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh + 0.1f)); // usage
                                                                                           // above
                                                                                           // critical
                                                                                           // by 10%
        assertTrue(newfakeHeapUsage > 0);
        assertTrue(newfakeHeapUsage <= fakeHeapMaxSize);
        hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
        getCache().getLogger().fine(removeExpectedExString);
        assertTrue(hmm.getState().isCritical());
        k = 5;
        Integer expectedInvocations10 = expectedInvocations.incrementAndGet();
        assertEquals(k.toString(), r.get(k, expectedInvocations10)); // load for key 5
        assertTrue(r.containsKey(k));
        assertEquals(k.toString(), r.get(k, expectedInvocations10)); // no load

        // Clean up critical state
        newfakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.3f)); // below
                                                                                      // critical by
                                                                                      // 30%
        assertTrue(fakeHeapMaxSize > 0);
        getCache().getLogger().fine(addExpectedBelow);
        hmm.updateStateAndSendEvent(newfakeHeapUsage, "test");
        getCache().getLogger().fine(removeExpectedBelow);
        assertFalse(hmm.getState().isCritical());
        return expectedInvocations10;
      }
    });

    accessor.invoke(removeExpectedException);
    ds1.invoke(removeExpectedException);
  }

  private CacheSerializableRunnable createPR(final String rName, final boolean accessor,
      final int fakeHeapMaxSize, final float criticalHeapThresh) {
    return new CacheSerializableRunnable("create PR accessor") {
      @Override
      public void run2() throws CacheException {
        // Assert some level of connectivity
        InternalDistributedSystem ds = getSystem();
        assertTrue(ds.getDistributionManager().getNormalDistributionManagerIds().size() >= 2);

        final long fakeHeapUsage = Math.round(fakeHeapMaxSize * (criticalHeapThresh - 0.5f)); // below
                                                                                              // critical
                                                                                              // by
                                                                                              // 50%
        InternalResourceManager irm = (InternalResourceManager) getCache().getResourceManager();
        HeapMemoryMonitor hmm = irm.getHeapMonitor();
        assertTrue(fakeHeapMaxSize > 0);
        hmm.setTestMaxMemoryBytes(fakeHeapMaxSize);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(fakeHeapUsage);
        irm.setCriticalHeapPercentage((criticalHeapThresh * 100.0f));
        assertFalse(hmm.getState().isCritical());
        AttributesFactory<Integer, String> af = new AttributesFactory<>();
        if (!accessor) {
          af.setCacheLoader(new CacheLoader<Integer, String>() {
            final AtomicInteger numLoaderInvocations = new AtomicInteger();

            @Override
            public String load(LoaderHelper<Integer, String> helper) throws CacheLoaderException {
              Integer expectedInvocations = (Integer) helper.getArgument();
              final int actualInvocations = numLoaderInvocations.getAndIncrement();
              if (expectedInvocations != actualInvocations) {
                throw new CacheLoaderException("Expected " + expectedInvocations
                    + " invocations, actual is " + actualInvocations);
              }
              return helper.getKey().toString();
            }

            @Override
            public void close() {}
          });

          af.setPartitionAttributes(new PartitionAttributesFactory().create());
        } else {
          af.setPartitionAttributes(new PartitionAttributesFactory().setLocalMaxMemory(0).create());
        }
        getCache().createRegion(rName, af.create());
      }
    };
  }

  private final SerializableRunnable addExpectedException =
      new SerializableRunnable("addExpectedEx") {
        @Override
        public void run() {
          getCache().getLogger().fine(addExpectedExString);
        }
      };

  private final SerializableRunnable removeExpectedException =
      new SerializableRunnable("removeExpectedException") {
        @Override
        public void run() {
          getCache().getLogger().fine(removeExpectedExString);
        }
      };

  private final SerializableRunnable addExpectedFunctionException =
      new SerializableRunnable("addExpectedFunctionException") {
        @Override
        public void run() {
          getCache().getLogger().fine(addExpectedFunctionExString);
        }
      };

  private final SerializableRunnable removeExpectedFunctionException =
      new SerializableRunnable("removeExpectedFunctionException") {
        @Override
        public void run() {
          getCache().getLogger().fine(removeExpectedFunctionExString);
        }
      };

  @Test
  public void testCriticalMemoryEventTolerance() {
    testMemoryEventTolerance(true);
  }

  @Test
  public void testEvictionMemoryEventTolerance() {
    testMemoryEventTolerance(false);
  }

  private void testMemoryEventTolerance(boolean isCritical) {
    final Host host = Host.getHost(0);
    final VM vm = host.getVM(0);
    vm.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        HeapMemoryMonitor.setTestDisableMemoryUpdates(false);
        GemFireCacheImpl cache = (GemFireCacheImpl) getCache();
        InternalResourceManager irm = cache.getInternalResourceManager();
        HeapMemoryMonitor hmm = irm.getHeapMonitor();
        hmm.setTestMaxMemoryBytes(100);
        HeapMemoryMonitor.setTestBytesUsedForThresholdSet(1);

        if (isCritical) {
          irm.setCriticalHeapPercentage(95);
        } else {
          irm.setEvictionHeapPercentage(50);
        }

        int previousMemoryStateChangeTolerance = hmm.getMemoryStateChangeTolerance();
        final int criticalBytesUsed = 96;
        final int evictionBytesUsed = 55;
        final int memoryStateChangeTolerance = 3;

        try {
          hmm.setMemoryStateChangeTolerance(memoryStateChangeTolerance);

          for (int i = 0; i < memoryStateChangeTolerance; i++) {
            if (isCritical) {
              hmm.updateStateAndSendEvent(criticalBytesUsed, "test");
              assertFalse(hmm.getState().isCritical());
            } else {
              hmm.updateStateAndSendEvent(evictionBytesUsed, "test");
              assertFalse(hmm.getState().isEviction());
            }
          }
          if (isCritical) {
            // Adding expected strings so we do not fail the
            // test prematurely
            getCache().getLogger().fine(addExpectedExString);
            hmm.updateStateAndSendEvent(criticalBytesUsed, "test");
            assertTrue(hmm.getState().isCritical());
            getCache().getLogger().fine(removeExpectedExString);
            getCache().getLogger().fine(addExpectedBelow);
            final int belowCriticalBytes = 92;
            hmm.updateStateAndSendEvent(belowCriticalBytes, "test");
            getCache().getLogger().fine(removeExpectedBelow);
            assertFalse(hmm.getState().isCritical());
          } else {
            hmm.updateStateAndSendEvent(evictionBytesUsed, "test");
            assertTrue(hmm.getState().isEviction());
            final int belowEvictionBytes = 45;
            hmm.updateStateAndSendEvent(belowEvictionBytes, "test");
            assertFalse(hmm.getState().isEviction());

          }

          HeapMemoryMonitor.setTestDisableMemoryUpdates(true);
        } finally {
          hmm.setMemoryStateChangeTolerance(previousMemoryStateChangeTolerance);
        }
        return null;
      }
    });
  }

  /**
   * Used to return and report remote CacheServer port info.
   */
  private static class ServerPorts implements Serializable {
    private final int port;

    ServerPorts(int port) {
      this.port = port;
    }

    int getPort() {
      return port;
    }

  }
}
