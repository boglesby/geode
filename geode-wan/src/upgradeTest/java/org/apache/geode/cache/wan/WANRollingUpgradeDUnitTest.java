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
package org.apache.geode.cache.wan;

import static org.apache.geode.distributed.ConfigurationProperties.DISTRIBUTED_SYSTEM_ID;
import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_CLUSTER_CONFIGURATION;
import static org.apache.geode.distributed.ConfigurationProperties.HTTP_SERVICE_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.JMX_MANAGER;
import static org.apache.geode.distributed.ConfigurationProperties.JMX_MANAGER_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.JMX_MANAGER_START;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.REMOTE_LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.USE_CLUSTER_CONFIGURATION;
import static org.apache.geode.internal.AvailablePortHelper.getRandomAvailableTCPPort;
import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.partition.PartitionRegionHelper;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.apache.geode.distributed.Locator;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.internal.cache.ForceReattemptException;
import org.apache.geode.internal.cache.wan.AbstractGatewaySender;
import org.apache.geode.internal.cache.wan.parallel.BatchRemovalThreadHelper;
import org.apache.geode.internal.cache.wan.parallel.ConcurrentParallelGatewaySenderQueue;
import org.apache.geode.internal.cache.wan.parallel.ParallelGatewaySenderQueue;
import org.apache.geode.management.internal.cli.util.CommandStringBuilder;
import org.apache.geode.management.internal.i18n.CliStrings;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.IgnoredException;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.dunit.internal.DUnitLauncher;
import org.apache.geode.test.junit.categories.WanTest;
import org.apache.geode.test.junit.rules.GfshCommandRule;
import org.apache.geode.test.junit.runners.CategoryWithParameterizedRunnerFactory;
import org.apache.geode.test.version.VersionManager;

@SuppressWarnings("ConstantConditions")
@Category(WanTest.class)
@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(CategoryWithParameterizedRunnerFactory.class)
public abstract class WANRollingUpgradeDUnitTest extends JUnit4CacheTestCase {
  @Parameterized.Parameters(name = "from_v{0}")
  public static Collection data() {
    List<String> result = VersionManager.getInstance().getVersionsWithoutCurrent();
    if (result.size() < 1) {
      throw new RuntimeException("No older versions of Geode were found to test against");
    } else {
      System.out.println("running against these versions: " + result);
    }
    return result;
  }

  // the old version of Geode we're testing against
  @Parameterized.Parameter
  public String oldVersion;

  @Rule
  public transient GfshCommandRule gfsh = new GfshCommandRule();

  void startLocator(int port, int distributedSystemId, String locators,
      String remoteLocators) throws IOException {
    startLocator(port, distributedSystemId, locators,
        remoteLocators, false);
  }

  void startLocator(int port, int distributedSystemId, String locators,
      String remoteLocators, boolean enableClusterConfiguration) throws IOException {
    Properties props = getLocatorProperties(distributedSystemId, locators, remoteLocators,
        enableClusterConfiguration);
    Locator.startLocatorAndDS(port, null, props);
  }

  SerializableRunnable startLocatorWithJmxManager(int port, int distributedSystemId,
      String locators, String remoteLocators, int jmxManagerPort) {
    return new SerializableRunnable() {
      @Override
      public void run() throws Exception {
        Properties props = getLocatorProperties(distributedSystemId, locators, remoteLocators);
        props.put(JMX_MANAGER_PORT, String.valueOf(jmxManagerPort));
        props.put(JMX_MANAGER, "true");
        props.put(JMX_MANAGER_START, "true");
        Locator.startLocatorAndDS(port, null, props);
      }
    };
  }

  private Properties getLocatorProperties(int distributedSystemId, String locators,
      String remoteLocators) {
    return getLocatorProperties(distributedSystemId, locators,
        remoteLocators, false);
  }


  private Properties getLocatorProperties(int distributedSystemId, String locators,
      String remoteLocators, boolean enableClusterConfiguration) {
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(DISTRIBUTED_SYSTEM_ID, String.valueOf(distributedSystemId));
    props.setProperty(LOCATORS, locators);
    if (remoteLocators != null) {
      props.setProperty(REMOTE_LOCATORS, remoteLocators);
    }
    props.setProperty(LOG_LEVEL, DUnitLauncher.logLevel);
    props.setProperty(ENABLE_CLUSTER_CONFIGURATION, String.valueOf(enableClusterConfiguration));
    // Turn off HTTP service, otherwise second (and subsequent) locators will see a port conflict
    props.setProperty(HTTP_SERVICE_PORT, String.valueOf(0));
    return props;
  }

  void stopLocator() {
    InternalLocator.getLocator().stop();
  }

  VM rollLocatorToCurrent(VM rollLocator, int port, int distributedSystemId,
      String locators, String remoteLocators) {
    return rollLocatorToCurrent(rollLocator, port, distributedSystemId,
        locators, remoteLocators, false);
  }

  VM rollLocatorToCurrent(VM rollLocator, int port, int distributedSystemId,
      String locators, String remoteLocators, boolean enableClusterConfiguration) {
    rollLocator.invoke(this::stopLocator);
    VM newLocator = Host.getHost(0).getVM(VersionManager.CURRENT_VERSION, rollLocator.getId());
    newLocator.invoke(() -> startLocator(port, distributedSystemId, locators, remoteLocators,
        enableClusterConfiguration));
    return newLocator;
  }

  VM rollStartAndConfigureServerToCurrent(VM oldServer, String locators,
      int distributedSystem, String regionName, String senderId, int messageSyncInterval) {
    oldServer.invoke(JUnit4CacheTestCase::closeCache);
    VM rollServer = Host.getHost(0).getVM(VersionManager.CURRENT_VERSION, oldServer.getId());
    startAndConfigureServers(rollServer, null, locators, distributedSystem, regionName, senderId,
        messageSyncInterval);
    return rollServer;
  }

  void startAndConfigureServers(VM server1, VM server2, String locators,
      int distributedSystem, String regionName, String senderId, int messageSyncInterval) {
    // Start and configure servers
    // - Create Cache
    // - Create CacheServer
    // - Create GatewaySender
    // - Create GatewayReceiver
    // - Create Region

    // Start and configure server 1
    server1.invoke(() -> createCache(locators));
    int server1Port = getRandomAvailableTCPPort();
    server1.invoke(addCacheServer(server1Port));
    server1.invoke(() -> createGatewaySender(senderId, distributedSystem, messageSyncInterval));
    server1.invoke(this::createGatewayReceiver);
    server1.invoke(() -> createPartitionedRegion(regionName, senderId));

    // Start and configure server 2 if necessary
    if (server2 != null) {
      int server2Port = getRandomAvailableTCPPort();
      server2.invoke(() -> createCache(locators));
      server2.invoke(addCacheServer(server2Port));
      server2.invoke(() -> createGatewaySender(senderId, distributedSystem, messageSyncInterval));
      server2.invoke(this::createGatewayReceiver);
      server2.invoke(() -> createPartitionedRegion(regionName, senderId));
    }
  }

  void doClientPutsAndVerifyEvents(VM client, VM localServer1, VM localServer2,
      VM remoteServer1, VM remoteServer2, String hostName, int locatorPort, String regionName,
      int numPuts, String senderId, boolean primaryOnly) {
    // Start client
    client.invoke(() -> startClient(hostName, locatorPort, regionName));

    // Do puts from client
    client.invoke(() -> doPuts(regionName, numPuts));

    // Wait for local site queues to be empty
    localServer1.invoke(() -> waitForQueueRegionToCertainSize(senderId, 0, primaryOnly));
    localServer2.invoke(() -> waitForQueueRegionToCertainSize(senderId, 0, primaryOnly));

    // Verify remote site received events
    int remoteServer1EventsReceived = remoteServer1.invoke(() -> getEventsReceived(regionName));
    int remoteServer2EventsReceived = remoteServer2.invoke(() -> getEventsReceived(regionName));
    assertThat(remoteServer1EventsReceived + remoteServer2EventsReceived).isEqualTo(numPuts);

    // Clear events received in both sites
    localServer1.invoke(() -> clearEventsReceived(regionName));
    localServer2.invoke(() -> clearEventsReceived(regionName));
    remoteServer1.invoke(() -> clearEventsReceived(regionName));
    remoteServer2.invoke(() -> clearEventsReceived(regionName));
  }

  void stopSenderAndVerifyEvents(VM localServer1, VM localServer2, VM remoteServer1,
      VM remoteServer2, String senderId, String regionName, int numPuts) {
    // Verify the secondary events still exist
    int localServer1QueueSize = localServer1.invoke(() -> getQueueRegionSize(senderId, false));
    int localServer2QueueSize = localServer2.invoke(() -> getQueueRegionSize(senderId, false));
    // The actual number of events in the queues can be greater than the number of puts in the case
    // of a client timeout / failover
    assertThat(localServer1QueueSize + localServer2QueueSize).isGreaterThanOrEqualTo(numPuts);

    // Stop one sender
    localServer1.invoke(JUnit4CacheTestCase::closeCache);

    // Wait for the other sender's queue to be empty
    localServer2.invoke(() -> waitForQueueRegionToCertainSize(senderId, 0, false));

    // Verify remote site did not receive any events. The events received were previously cleared on
    // all members, so there should be 0 events received on the remote site.
    int remoteServer1EventsReceived = remoteServer1.invoke(() -> getEventsReceived(regionName));
    int remoteServer2EventsReceived = remoteServer2.invoke(() -> getEventsReceived(regionName));
    assertThat(remoteServer1EventsReceived + remoteServer2EventsReceived).isEqualTo(0);
  }

  String getCreateGatewaySenderCommand(String id, int remoteDsId) {
    CommandStringBuilder csb = new CommandStringBuilder(CliStrings.CREATE_GATEWAYSENDER);
    csb.addOption(CliStrings.CREATE_GATEWAYSENDER__ID, id);
    csb.addOption(CliStrings.CREATE_GATEWAYSENDER__REMOTEDISTRIBUTEDSYSTEMID,
        String.valueOf(remoteDsId));
    return csb.toString();
  }

  public void createCache(String locators) {
    createCache(locators, false, false);
  }

  public void createCache(String locators, boolean enableClusterConfiguration,
      boolean useClusterConfiguration) {
    Properties props = new Properties();
    props.setProperty(ENABLE_CLUSTER_CONFIGURATION, String.valueOf(enableClusterConfiguration));
    props.setProperty(USE_CLUSTER_CONFIGURATION, String.valueOf(useClusterConfiguration));
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, locators);
    props.setProperty(LOG_LEVEL, DUnitLauncher.logLevel);
    getCache(props);
  }

  private SerializableRunnable addCacheServer(int port) {
    return new SerializableRunnable() {
      @Override
      public void run() throws Exception {
        CacheServer server = getCache().addCacheServer();
        server.setPort(port);
        server.start();
      }
    };
  }

  protected void startClient(String hostName, int locatorPort, String regionName) {
    ClientCacheFactory ccf = new ClientCacheFactory().addPoolLocator(hostName, locatorPort);
    ClientCache cache = getClientCache(ccf);
    cache.createClientRegionFactory(ClientRegionShortcut.PROXY).create(regionName);
  }

  void createGatewaySender(String id, int remoteDistributedSystemId,
      int messageSyncInterval) {
    // Setting the messageSyncInterval controls how often the BatchRemovalThread sends processed
    // events from the primary to the secondary. Setting it high prevents the events from being
    // removed from the secondary.
    BatchRemovalThreadHelper.setMessageSyncInterval(messageSyncInterval);
    GatewaySenderFactory gsf = getCache().createGatewaySenderFactory();
    gsf.setParallel(true);
    gsf.create(id, remoteDistributedSystemId);
  }

  void resetAllMessageSyncIntervals(VM site1Server1, VM site1Server2, VM site2Server1,
      VM site2Server2) {
    site1Server1.invoke(this::resetMessageSyncInterval);
    site1Server2.invoke(this::resetMessageSyncInterval);
    site2Server1.invoke(this::resetMessageSyncInterval);
    site2Server2.invoke(this::resetMessageSyncInterval);
  }

  private void resetMessageSyncInterval() {
    BatchRemovalThreadHelper
        .setMessageSyncInterval(ParallelGatewaySenderQueue.DEFAULT_MESSAGE_SYNC_INTERVAL);
  }

  void createGatewayReceiver() {
    getCache().createGatewayReceiverFactory().create();
  }

  private void createPartitionedRegion(String regionName, String gatewaySenderId) {
    PartitionAttributesFactory paf = new PartitionAttributesFactory();
    paf.setRedundantCopies(1);
    paf.setTotalNumBuckets(10);
    getCache().createRegionFactory(RegionShortcut.PARTITION_REDUNDANT)
        .addCacheListener(new EventCountCacheListener()).addGatewaySenderId(gatewaySenderId)
        .setPartitionAttributes(paf.create()).create(regionName);
  }

  protected void doPuts(String regionName, int numPuts) {
    Region region = getCache().getRegion(regionName);
    for (int i = 0; i < numPuts; i++) {
      region.put(i, i);
    }
  }

  protected void pauseSender(String senderId) {
    final IgnoredException exln = IgnoredException.addIgnoredException("Could not connect");
    IgnoredException exp =
        IgnoredException.addIgnoredException(ForceReattemptException.class.getName());
    try {
      Set<GatewaySender> senders = cache.getGatewaySenders();
      GatewaySender sender = null;
      for (GatewaySender s : senders) {
        if (s.getId().equals(senderId)) {
          sender = s;
          break;
        }
      }
      sender.pause();
      ((AbstractGatewaySender) sender).getEventProcessor().waitForDispatcherToPause();

    } finally {
      exp.remove();
      exln.remove();
    }
  }

  protected void resumeSender(String senderId) {
    final IgnoredException exln = IgnoredException.addIgnoredException("Could not connect");
    IgnoredException exp =
        IgnoredException.addIgnoredException(ForceReattemptException.class.getName());
    try {
      Set<GatewaySender> senders = cache.getGatewaySenders();
      GatewaySender sender = null;
      for (GatewaySender s : senders) {
        if (s.getId().equals(senderId)) {
          sender = s;
          break;
        }
      }
      sender.resume();
    } finally {
      exp.remove();
      exln.remove();
    }
  }

  protected void waitForQueueRegionToCertainSize(String gatewaySenderId, int expectedSize,
      boolean primaryOnly) {
    await()
        .until(() -> getQueueRegionSize(gatewaySenderId, primaryOnly) == expectedSize);
  }

  protected int getQueueRegionSize(String gatewaySenderId, boolean primaryOnly) {
    // This method currently only supports parallel senders. It gets the size of the local data set
    // from the
    // underlying co-located region. Depending on the value of primaryOnly, it gets either the local
    // primary data set (just primary buckets) or all local data set (primary and secondary
    // buckets).
    AbstractGatewaySender ags =
        (AbstractGatewaySender) getCache().getGatewaySender(gatewaySenderId);
    ConcurrentParallelGatewaySenderQueue prq =
        (ConcurrentParallelGatewaySenderQueue) ags.getQueues().iterator().next();
    Region region = prq.getRegion();
    Region localDataSet = primaryOnly ? PartitionRegionHelper.getLocalPrimaryData(region)
        : PartitionRegionHelper.getLocalData(region);
    return localDataSet.size();
  }

  protected Integer getEventsReceived(String regionName) {
    Region region = getCache().getRegion(regionName);
    EventCountCacheListener cl =
        (EventCountCacheListener) region.getAttributes().getCacheListener();
    return cl.getEventsReceived();
  }

  protected void clearEventsReceived(String regionName) {
    Region region = getCache().getRegion(regionName);
    EventCountCacheListener cl =
        (EventCountCacheListener) region.getAttributes().getCacheListener();
    cl.clearEventsReceived();
  }

  static class EventCountCacheListener extends CacheListenerAdapter {

    AtomicInteger eventsReceived = new AtomicInteger();

    @Override
    public void afterCreate(EntryEvent event) {
      process(event);
    }

    @Override
    public void afterUpdate(EntryEvent event) {
      process(event);
    }

    void process(EntryEvent event) {
      incrementEventsReceived();
    }

    int incrementEventsReceived() {
      return eventsReceived.incrementAndGet();
    }

    int getEventsReceived() {
      return eventsReceived.get();
    }

    void clearEventsReceived() {
      eventsReceived.set(0);
    }
  }
}
