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
package org.apache.geode.test.dunit;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.geode.test.dunit.internal.ChildVMLauncher;
import org.apache.geode.test.dunit.internal.ProcessHolder;
import org.apache.geode.test.dunit.internal.RemoteDUnitVMIF;
import org.apache.geode.test.dunit.internal.VMEventNotifier;
import org.apache.geode.test.version.VersionManager;

/**
 * This class represents a host on which a remote method may be invoked. It provides access to the
 * VMs and GemFire systems that run on that host.
 *
 * <p>
 * Additionally, it provides access to the Java RMI registry that runs on the host. By default, an
 * RMI registry is only started on the host on which Hydra's Master VM runs. RMI registries may be
 * started on other hosts via additional Hydra configuration.
 */
@SuppressWarnings("serial")
public abstract class Host implements Serializable {

  /** The available hosts */
  private static final List<Host> hosts = new ArrayList<>();

  private static VM locator;

  /** The name of this host machine */
  private final String hostName;

  /** The VMs that run on this host */
  private final List<VM> vms;

  private final transient VMEventNotifier vmEventNotifier;

  /*
   * Returns the number of known hosts
   */
  public static int getHostCount() {
    return hosts.size();
  }

  /*
   * Makes note of a new Host
   */
  protected static void addHost(Host host) {
    hosts.add(host);
  }

  /**
   * Returns a given host
   *
   * @param whichHost A zero-based identifier of the host
   * @return the Host
   *
   * @throws IllegalArgumentException {@code n} is more than the number of hosts
   */
  public static Host getHost(int whichHost) {
    int size = hosts.size();
    if (whichHost >= size) {
      String message = "Cannot request host " + whichHost + ".  There are only " + size + " hosts.";
      throw new IllegalArgumentException(message);

    } else {
      return hosts.get(whichHost);
    }
  }

  /**
   * Reset all VMs to be using the current version of Geode. Some backward-compatibility tests will
   * set a VM to a different version. This will ensure that all are using the current build.
   */
  public static void setAllVMsToCurrentVersion() {
    int numHosts = getHostCount();
    for (int hostIndex = 0; hostIndex < numHosts; hostIndex++) {
      Host host = Host.getHost(hostIndex);
      int numVMs = host.getVMCount();
      for (int i = 0; i < numVMs; i++) {
        try {
          host.getVM(VersionManager.CURRENT_VERSION, i);
        } catch (UnsupportedOperationException e) {
          // not all implementations support versioning
        }
      }
    }
  }

  /**
   * Creates a new {@code Host} with the given name
   *
   * @param hostName the name of the Host
   * @param vmEventNotifier the notifier to use for the Host
   */
  protected Host(String hostName, VMEventNotifier vmEventNotifier) {
    if (hostName == null) {
      String message = "Cannot create a Host with a null name";
      throw new NullPointerException(message);
    }

    this.hostName = hostName;
    vms = new ArrayList<>();
    this.vmEventNotifier = vmEventNotifier;
  }

  /*
   * Returns the machine name of this host
   */
  public String getHostName() {
    return hostName;
  }

  /*
   * Returns the number of VMs that run on this host
   */
  public int getVMCount() {
    return vms.size();
  }

  /**
   * Returns a VM that runs on this host
   *
   * @param n A zero-based identifier of the VM
   * @return a VM that runs on this host
   *
   * @throws IllegalArgumentException {@code n} is more than the number of VMs
   * @deprecated use the static methods in VM instead
   */
  @Deprecated
  public VM getVM(int n) {
    int size = vms.size();
    if (n >= size) {
      String s = "Cannot request VM " + n + ".  There are only " + size + " VMs on " + this;
      throw new IllegalArgumentException(s);

    } else {
      VM vm = vms.get(n);
      vm.makeAvailable();
      return vm;
    }
  }

  /*
   * return a collection of all VMs
   */
  public List<VM> getAllVMs() {
    return new ArrayList<>(vms);
  }

  /*
   * Returns the nth VM of the given version. Optional operation currently supported only in
   * distributedTests.
   */
  public VM getVM(String version, int n) {
    throw new UnsupportedOperationException("Not supported in this implementation of Host");
  }

  /*
   * Adds a VM to this Host with the given process id and client record.
   */
  protected void addVM(int vmid, final String version, RemoteDUnitVMIF client,
      ProcessHolder processHolder,
      ChildVMLauncher childVMLauncher) {
    VM vm = new VM(this, version, vmid, client, processHolder,
        childVMLauncher);
    vms.add(vm);
    vmEventNotifier.notifyAfterCreateVM(vm);
  }

  public static VM getLocator() {
    return locator;
  }

  private static void setLocator(VM l) {
    locator = l;
  }

  protected void addLocator(int vmid, RemoteDUnitVMIF client, ProcessHolder processHolder,
      ChildVMLauncher childVMLauncher) {
    setLocator(new VM(this, VersionManager.CURRENT_VERSION, vmid, client, processHolder,
        childVMLauncher));
  }

  @Override
  public String toString() {
    return "Host " + getHostName()
        + " with "
        + getVMCount()
        + " VMs";
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof Host) {
      return ((Host) o).getHostName().equals(getHostName());

    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return getHostName().hashCode();
  }

  VMEventNotifier getVMEventNotifier() {
    return vmEventNotifier;
  }
}
