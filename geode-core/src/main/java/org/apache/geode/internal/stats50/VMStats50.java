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
package org.apache.geode.internal.stats50;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import org.apache.geode.StatisticDescriptor;
import org.apache.geode.Statistics;
import org.apache.geode.StatisticsFactory;
import org.apache.geode.StatisticsType;
import org.apache.geode.StatisticsTypeFactory;
import org.apache.geode.SystemFailure;
import org.apache.geode.annotations.Immutable;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.internal.classloader.ClassPathLoader;
import org.apache.geode.internal.statistics.StatisticsTypeFactoryImpl;
import org.apache.geode.internal.statistics.VMStatsContract;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * Statistics related to a Java VM. This version is hardcoded to use 1.5 MXBean stats from
 * java.lang.management.
 */
public class VMStats50 implements VMStatsContract {
  private static final Logger logger = LogService.getLogger(VMStats50.class.getName());

  @Immutable
  private static final StatisticsType vmType;

  @Immutable
  private static final ClassLoadingMXBean clBean;
  @Immutable
  private static final MemoryMXBean memBean;
  @Immutable
  private static final OperatingSystemMXBean osBean;
  /**
   * This is actually an instance of UnixOperatingSystemMXBean but this class is not available on
   * Windows so needed to make this a runtime check.
   */
  @Immutable
  private static final Object unixBean;
  @Immutable
  private static final Method getMaxFileDescriptorCount;
  @Immutable
  private static final Method getOpenFileDescriptorCount;
  @Immutable
  private static final Method getProcessCpuTime;
  @Immutable
  private static final ThreadMXBean threadBean;

  private static final int pendingFinalizationCountId;
  private static final int loadedClassesId;
  private static final int unloadedClassesId;

  private static final int daemonThreadsId;
  private static final int peakThreadsId;
  private static final int threadsId;
  private static final int threadStartsId;

  private static final int cpusId;
  private static final int freeMemoryId;
  private static final int totalMemoryId;
  private static final int maxMemoryId;

  @Immutable
  private static final StatisticsType memoryUsageType;
  private static final int mu_initMemoryId;
  private static final int mu_maxMemoryId;
  private static final int mu_usedMemoryId;
  private static final int mu_committedMemoryId;

  @Immutable
  private static final StatisticsType gcType;
  private static final int gc_collectionsId;
  private static final int gc_collectionTimeId;
  @MakeNotStatic
  private final Map<GarbageCollectorMXBean, Statistics> gcMap =
      new HashMap<>();

  @Immutable
  private static final StatisticsType mpType;
  private static final int mp_l_initMemoryId;
  private static final int mp_l_maxMemoryId;
  private static final int mp_l_usedMemoryId;
  private static final int mp_l_committedMemoryId;
  private static final int mp_gc_usedMemoryId;
  private static final int mp_usageThresholdId;
  private static final int mp_collectionUsageThresholdId;
  private static final int mp_usageExceededId;
  private static final int mp_collectionUsageExceededId;
  @MakeNotStatic
  private final Map<MemoryPoolMXBean, Statistics> mpMap =
      new HashMap<>();

  private static final int unix_fdLimitId;
  private static final int unix_fdsOpenId;
  private static final int processCpuTimeId;
  private long threadStartCount = 0;
  private long[] allThreadIds = null;
  private static final boolean THREAD_STATS_ENABLED =
      Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "enableThreadStats");
  private final Map<Long, ThreadStatInfo> threadMap =
      THREAD_STATS_ENABLED ? new HashMap<>() : null;
  @Immutable
  private static final StatisticsType threadType;
  private static final int thread_blockedId;
  private static final int thread_lockOwnerId;
  private static final int thread_waitedId;
  private static final int thread_inNativeId;
  private static final int thread_suspendedId;
  private static final int thread_blockedTimeId;
  private static final int thread_waitedTimeId;
  private static final int thread_cpuTimeId;
  private static final int thread_userTimeId;

  @Immutable
  private static final BufferPoolStats bufferPoolStats;

  static {
    clBean = ManagementFactory.getClassLoadingMXBean();
    memBean = ManagementFactory.getMemoryMXBean();
    osBean = ManagementFactory.getOperatingSystemMXBean();
    {
      Method m1 = null;
      Method m2 = null;
      Method m3 = null;
      Object bean = null;
      try {
        Class<?> c =
            ClassPathLoader.getLatest().forName("com.sun.management.UnixOperatingSystemMXBean");
        if (c.isInstance(osBean)) {
          m1 = c.getMethod("getMaxFileDescriptorCount");
          m2 = c.getMethod("getOpenFileDescriptorCount");
          bean = osBean;
        }

        // Always set ProcessCpuTime
        m3 = osBean.getClass().getMethod("getProcessCpuTime");
        m3.setAccessible(true);
      } catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable ex) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        logger.warn(ex.getMessage());
        SystemFailure.checkFailure();
        // must be on a platform that does not support unix mxbean
        bean = null;
        m1 = null;
        m2 = null;
        m3 = null;
      } finally {
        unixBean = bean;
        getMaxFileDescriptorCount = m1;
        getOpenFileDescriptorCount = m2;
        getProcessCpuTime = m3;
      }
    }
    threadBean = ManagementFactory.getThreadMXBean();
    if (THREAD_STATS_ENABLED) {
      if (threadBean.isThreadCpuTimeSupported()) {
        if (!threadBean.isThreadCpuTimeEnabled()) {
          if (Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "enableCpuTime")) {
            threadBean.setThreadCpuTimeEnabled(true);
          }
        }
      }
      if (threadBean.isThreadContentionMonitoringSupported()) {
        if (!threadBean.isThreadContentionMonitoringEnabled()) {
          if (Boolean.getBoolean(GeodeGlossary.GEMFIRE_PREFIX + "enableContentionTime")) {
            threadBean.setThreadContentionMonitoringEnabled(true);
          }
        }
      }
    }
    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();
    List<StatisticDescriptor> sds = new ArrayList<>();
    sds.add(f.createLongGauge("pendingFinalization",
        "Number of objects that are pending finalization in the java VM.", "objects"));
    sds.add(f.createLongGauge("daemonThreads", "Current number of live daemon threads in this VM.",
        "threads"));
    sds.add(f.createLongGauge("threads",
        "Current number of live threads (both daemon and non-daemon) in this VM.", "threads"));
    sds.add(
        f.createLongGauge("peakThreads", "High water mark of live threads in this VM.", "threads"));
    sds.add(f.createLongCounter("threadStarts",
        "Total number of times a thread has been started since this vm started.", "threads"));
    sds.add(f.createLongGauge("cpus", "Number of cpus available to the java VM on its machine.",
        "cpus", true));
    sds.add(f.createLongCounter("loadedClasses", "Total number of classes loaded since vm started.",
        "classes"));
    sds.add(f.createLongCounter("unloadedClasses",
        "Total number of classes unloaded since vm started.", "classes", true));
    sds.add(f.createLongGauge("freeMemory",
        "An approximation of the total amount of memory currently available for future allocated objects, measured in bytes.",
        "bytes", true));
    sds.add(f.createLongGauge("totalMemory",
        "The total amount of memory currently available for current and future objects, measured in bytes.",
        "bytes"));
    sds.add(f.createLongGauge("maxMemory",
        "The maximum amount of memory that the VM will attempt to use, measured in bytes.", "bytes",
        true));
    sds.add(f.createLongCounter("processCpuTime", "CPU timed used by the process in nanoseconds.",
        "nanoseconds"));
    if (unixBean != null) {
      sds.add(f.createLongGauge("fdLimit", "Maximum number of file descriptors", "fds", true));
      sds.add(f.createLongGauge("fdsOpen", "Current number of open file descriptors", "fds"));
    }
    vmType = f.createType("VMStats", "Stats available on a 1.5 java virtual machine.",
        sds.toArray(new StatisticDescriptor[0]));
    pendingFinalizationCountId = vmType.nameToId("pendingFinalization");
    loadedClassesId = vmType.nameToId("loadedClasses");
    unloadedClassesId = vmType.nameToId("unloadedClasses");
    daemonThreadsId = vmType.nameToId("daemonThreads");
    peakThreadsId = vmType.nameToId("peakThreads");
    threadsId = vmType.nameToId("threads");
    threadStartsId = vmType.nameToId("threadStarts");
    cpusId = vmType.nameToId("cpus");
    freeMemoryId = vmType.nameToId("freeMemory");
    totalMemoryId = vmType.nameToId("totalMemory");
    maxMemoryId = vmType.nameToId("maxMemory");
    processCpuTimeId = vmType.nameToId("processCpuTime");
    if (unixBean != null) {
      unix_fdLimitId = vmType.nameToId("fdLimit");
      unix_fdsOpenId = vmType.nameToId("fdsOpen");
    } else {
      unix_fdLimitId = -1;
      unix_fdsOpenId = -1;
    }

    memoryUsageType =
        f.createType("VMMemoryUsageStats", "Stats available on a 1.5 memory usage area",
            new StatisticDescriptor[] {f.createLongGauge("initMemory",
                "Initial memory the vm requested from the operating system for this area", "bytes"),
                f.createLongGauge("maxMemory",
                    "The maximum amount of memory this area can have in bytes.", "bytes"),
                f.createLongGauge("usedMemory",
                    "The amount of used memory for this area, measured in bytes.", "bytes"),
                f.createLongGauge("committedMemory",
                    "The amount of committed memory for this area, measured in bytes.", "bytes")});
    mu_initMemoryId = memoryUsageType.nameToId("initMemory");
    mu_maxMemoryId = memoryUsageType.nameToId("maxMemory");
    mu_usedMemoryId = memoryUsageType.nameToId("usedMemory");
    mu_committedMemoryId = memoryUsageType.nameToId("committedMemory");

    gcType = f.createType("VMGCStats", "Stats available on a 1.5 garbage collector",
        new StatisticDescriptor[] {
            f.createLongCounter("collections",
                "Total number of collections this garbage collector has done.", "operations"),
            f.createLongCounter("collectionTime",
                "Approximate elapsed time spent doing collections by this garbage collector.",
                "milliseconds"),});
    gc_collectionsId = gcType.nameToId("collections");
    gc_collectionTimeId = gcType.nameToId("collectionTime");

    mpType =
        f.createType("VMMemoryPoolStats", "Stats available on a 1.5 memory pool",
            new StatisticDescriptor[] {f.createLongGauge("currentInitMemory",
                "Initial memory the vm requested from the operating system for this pool", "bytes"),
                f.createLongGauge("currentMaxMemory",
                    "The maximum amount of memory this pool can have in bytes.", "bytes"),
                f.createLongGauge("currentUsedMemory",
                    "The estimated amount of used memory currently in use for this pool, measured in bytes.",
                    "bytes"),
                f.createLongGauge("currentCommittedMemory",
                    "The amount of committed memory for this pool, measured in bytes.", "bytes"),
                // f.createLongGauge("collectionInitMemory",
                // "Initial memory the vm requested from the operating system for this pool",
                // "bytes"),
                // f.createLongGauge("collectionMaxMemory",
                // "The maximum amount of memory this pool can have in bytes.",
                // "bytes"),
                f.createLongGauge("collectionUsedMemory",
                    "The estimated amount of used memory after that last garbage collection of this pool, measured in bytes.",
                    "bytes"),
                // f.createLongGauge("collectionCommittedMemory",
                // "The amount of committed memory for this pool, measured in bytes.",
                // "bytes"),
                f.createLongGauge("collectionUsageThreshold",
                    "The collection usage threshold for this pool in bytes", "bytes"),
                f.createLongCounter("collectionUsageExceeded",
                    "Total number of times the garbage collector detected that memory usage in this pool exceeded the collectionUsageThreshold",
                    "exceptions"),
                f.createLongGauge("usageThreshold", "The usage threshold for this pool in bytes",
                    "bytes"),
                f.createLongCounter("usageExceeded",
                    "Total number of times that memory usage in this pool exceeded the usageThreshold",
                    "exceptions")});
    mp_l_initMemoryId = mpType.nameToId("currentInitMemory");
    mp_l_maxMemoryId = mpType.nameToId("currentMaxMemory");
    mp_l_usedMemoryId = mpType.nameToId("currentUsedMemory");
    mp_l_committedMemoryId = mpType.nameToId("currentCommittedMemory");
    // mp_gc_initMemoryId = mpType.nameToId("collectionInitMemory");
    // mp_gc_maxMemoryId = mpType.nameToId("collectionMaxMemory");
    mp_gc_usedMemoryId = mpType.nameToId("collectionUsedMemory");
    // mp_gc_committedMemoryId = mpType.nameToId("collectionCommittedMemory");
    mp_usageThresholdId = mpType.nameToId("usageThreshold");
    mp_collectionUsageThresholdId = mpType.nameToId("collectionUsageThreshold");
    mp_usageExceededId = mpType.nameToId("usageExceeded");
    mp_collectionUsageExceededId = mpType.nameToId("collectionUsageExceeded");

    if (THREAD_STATS_ENABLED) {
      threadType = f.createType("VMThreadStats", "Stats available on a 1.5 thread",
          new StatisticDescriptor[] {
              f.createLongCounter("blocked",
                  "Total number of times this thread blocked to enter or reenter a monitor",
                  "operations"),
              f.createLongCounter("blockedTime",
                  "Total amount of elapsed time, approximately, that this thread has spent blocked to enter or reenter a monitor. May need to be enabled by setting -Dgemfire.enableContentionTime=true",
                  "milliseconds"),
              f.createLongGauge("lockOwner",
                  "The thread id that owns the lock that this thread is blocking on.", "threadId"),
              f.createLongGauge("inNative", "1 if the thread is in native code.", "boolean"),
              f.createLongGauge("suspended", "1 if this thread is suspended", "boolean"),
              f.createLongCounter("waited",
                  "Total number of times this thread waited for notification.", "operations"),
              f.createLongCounter("waitedTime",
                  "Total amount of elapsed time, approximately, that this thread has spent waiting for notification. May need to be enabled by setting -Dgemfire.enableContentionTime=true",
                  "milliseconds"),
              f.createLongCounter("cpuTime",
                  "Total cpu time for this thread.  May need to be enabled by setting -Dgemfire.enableCpuTime=true.",
                  "nanoseconds"),
              f.createLongCounter("userTime",
                  "Total user time for this thread. May need to be enabled by setting -Dgemfire.enableCpuTime=true.",
                  "nanoseconds"),});
      thread_blockedId = threadType.nameToId("blocked");
      thread_waitedId = threadType.nameToId("waited");
      thread_lockOwnerId = threadType.nameToId("lockOwner");
      thread_inNativeId = threadType.nameToId("inNative");
      thread_suspendedId = threadType.nameToId("suspended");
      thread_blockedTimeId = threadType.nameToId("blockedTime");
      thread_waitedTimeId = threadType.nameToId("waitedTime");
      thread_cpuTimeId = threadType.nameToId("cpuTime");
      thread_userTimeId = threadType.nameToId("userTime");
    } else {
      threadType = null;
      thread_blockedId = -1;
      thread_waitedId = -1;
      thread_lockOwnerId = -1;
      thread_inNativeId = -1;
      thread_suspendedId = -1;
      thread_blockedTimeId = -1;
      thread_waitedTimeId = -1;
      thread_cpuTimeId = -1;
      thread_userTimeId = -1;
    }

    bufferPoolStats = new BufferPoolStats(f);
  }

  private final Statistics vmStats;
  private final Statistics heapMemStats;
  private final Statistics nonHeapMemStats;

  private final StatisticsFactory f;
  private final long id;

  public VMStats50(StatisticsFactory f, long id) {
    this.f = f;
    this.id = id;
    vmStats = f.createStatistics(vmType, "vmStats", id);
    heapMemStats = f.createStatistics(memoryUsageType, "vmHeapMemoryStats", id);
    nonHeapMemStats = f.createStatistics(memoryUsageType, "vmNonHeapMemoryStats", id);
    initMemoryPools();
    bufferPoolStats.init(f, id);
    initGC();
  }

  private boolean newThreadsStarted() {
    long curStarts = threadBean.getTotalStartedThreadCount();
    return curStarts > threadStartCount;
  }

  private void refreshThreads() {
    if (!THREAD_STATS_ENABLED) {
      return;
    }
    if (allThreadIds == null || newThreadsStarted()) {
      allThreadIds = threadBean.getAllThreadIds();
      threadStartCount = threadBean.getTotalStartedThreadCount();
    }
    ThreadInfo[] threadInfos = threadBean.getThreadInfo(allThreadIds, 0);
    for (int i = 0; i < threadInfos.length; i++) {
      long id = allThreadIds[i];
      ThreadInfo item = threadInfos[i];
      if (item != null) {
        ThreadStatInfo tsi = threadMap.get(id);
        if (tsi == null) {
          threadMap.put(id, new ThreadStatInfo(item, f.createStatistics(threadType,
              item.getThreadName() + '-' + item.getThreadId(), this.id)));
        } else {
          tsi.ti = item;
        }
      } else {
        ThreadStatInfo tsi = threadMap.remove(id);
        if (tsi != null) {
          tsi.s.close();
        }
      }
    }
    for (final Map.Entry<Long, ThreadStatInfo> me : threadMap.entrySet()) {
      long id = me.getKey();
      ThreadStatInfo tsi = me.getValue();
      ThreadInfo ti = tsi.ti;
      Statistics s = tsi.s;
      s.setLong(thread_blockedId, ti.getBlockedCount());
      s.setLong(thread_lockOwnerId, ti.getLockOwnerId());
      s.setLong(thread_waitedId, ti.getWaitedCount());
      s.setLong(thread_inNativeId, ti.isInNative() ? 1 : 0);
      s.setLong(thread_suspendedId, ti.isSuspended() ? 1 : 0);
      if (threadBean.isThreadContentionMonitoringSupported()
          && threadBean.isThreadContentionMonitoringEnabled()) {
        s.setLong(thread_blockedTimeId, ti.getBlockedTime());
        s.setLong(thread_waitedTimeId, ti.getWaitedTime());
      }
      if (threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled()) {
        s.setLong(thread_cpuTimeId, threadBean.getThreadCpuTime(id));
        s.setLong(thread_userTimeId, threadBean.getThreadUserTime(id));
      }
    }
  }

  /**
   * This set is used to workaround a JRockit bug 36348 in which getCollectionUsage throws
   * OperationUnsupportedException instead of returning null.
   */
  private final HashSet<String> collectionUsageUnsupported = new HashSet<>();

  /**
   * Returns true if collection usage is not supported on the given bean.
   */
  private boolean isCollectionUsageUnsupported(MemoryPoolMXBean mp) {
    return collectionUsageUnsupported.contains(mp.getName());
  }

  /**
   * Remember that the given bean does not support collection usage.
   */
  private void setCollectionUsageUnsupported(MemoryPoolMXBean mp) {
    collectionUsageUnsupported.add(mp.getName());
  }

  private void initMemoryPools() {
    List<MemoryPoolMXBean> l = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean item : l) {
      if (item.isValid() && !mpMap.containsKey(item)) {
        mpMap.put(item,
            f.createStatistics(mpType, item.getName() + '-' + item.getType(), id));
      }
    }
  }

  private void refreshMemoryPools() {
    boolean reInitPools = false;
    Iterator<Map.Entry<MemoryPoolMXBean, Statistics>> it = mpMap.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<MemoryPoolMXBean, Statistics> me = it.next();
      MemoryPoolMXBean mp = me.getKey();
      Statistics s = me.getValue();
      if (!mp.isValid()) {
        s.close();
        it.remove();
        reInitPools = true;
      } else {
        MemoryUsage mu;
        try {
          mu = mp.getUsage();
        } catch (IllegalArgumentException ex) {
          // to workaround JRockit bug 36348 just ignore this and try the next pool
          continue;
        } catch (InternalError ie) {
          // Somebody saw an InternalError once but I have no idea how to reproduce it. Was this a
          // race between
          // mp.isValid() and mp.getUsage()? Perhaps.
          s.close();
          it.remove();
          reInitPools = true;
          logger.warn("Accessing MemoryPool '{}' threw an Internal Error: {}", mp.getName(),
              ie.getMessage());
          continue;
        }
        s.setLong(mp_l_initMemoryId, mu.getInit());
        s.setLong(mp_l_usedMemoryId, mu.getUsed());
        s.setLong(mp_l_committedMemoryId, mu.getCommitted());
        s.setLong(mp_l_maxMemoryId, mu.getMax());
        if (mp.isUsageThresholdSupported()) {
          s.setLong(mp_usageThresholdId, mp.getUsageThreshold());
          s.setLong(mp_usageExceededId, mp.getUsageThresholdCount());
        }
        mu = null;
        if (!isCollectionUsageUnsupported(mp)) {
          try {
            mu = mp.getCollectionUsage();
          } catch (UnsupportedOperationException ex) {
            // JRockit throws this exception instead of returning null
            // as the javadocs say it should. See bug 36348
            setCollectionUsageUnsupported(mp);
          } catch (IllegalArgumentException ex) {
            // Yet another JRockit bug in which its code catches an assertion
            // about the state of their bean stat values being inconsistent.
            // See bug 36348.
            setCollectionUsageUnsupported(mp);
          }
        }
        if (mu != null) {
          // s.setLong(mp_gc_initMemoryId, mu.getInit());
          s.setLong(mp_gc_usedMemoryId, mu.getUsed());
          // s.setLong(mp_gc_committedMemoryId, mu.getCommitted());
          // s.setLong(mp_gc_maxMemoryId, mu.getMax());
          if (mp.isCollectionUsageThresholdSupported()) {
            s.setLong(mp_collectionUsageThresholdId, mp.getCollectionUsageThreshold());
            s.setLong(mp_collectionUsageExceededId, mp.getCollectionUsageThresholdCount());
          }
        }
      }
    }
    if (reInitPools) {
      initMemoryPools();
    }
  }

  private void initGC() {
    List<GarbageCollectorMXBean> l = ManagementFactory.getGarbageCollectorMXBeans();
    for (GarbageCollectorMXBean item : l) {
      if (item.isValid() && !gcMap.containsKey(item)) {
        gcMap.put(item, f.createStatistics(gcType, item.getName(), id));
      }
    }
  }

  private void refreshGC() {
    Iterator<Map.Entry<GarbageCollectorMXBean, Statistics>> it = gcMap.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<GarbageCollectorMXBean, Statistics> me = it.next();
      GarbageCollectorMXBean gc = me.getKey();
      Statistics s = me.getValue();
      if (!gc.isValid()) {
        s.close();
        it.remove();
      } else {
        s.setLong(gc_collectionsId, gc.getCollectionCount());
        s.setLong(gc_collectionTimeId, gc.getCollectionTime());
      }
    }
  }

  @Override
  public void refresh() {
    Runtime rt = Runtime.getRuntime();
    vmStats.setLong(pendingFinalizationCountId, memBean.getObjectPendingFinalizationCount());
    vmStats.setLong(cpusId, osBean.getAvailableProcessors());
    vmStats.setLong(threadsId, threadBean.getThreadCount());
    vmStats.setLong(daemonThreadsId, threadBean.getDaemonThreadCount());
    vmStats.setLong(peakThreadsId, threadBean.getPeakThreadCount());
    vmStats.setLong(threadStartsId, threadBean.getTotalStartedThreadCount());
    vmStats.setLong(loadedClassesId, clBean.getTotalLoadedClassCount());
    vmStats.setLong(unloadedClassesId, clBean.getUnloadedClassCount());
    vmStats.setLong(freeMemoryId, rt.freeMemory());
    vmStats.setLong(totalMemoryId, rt.totalMemory());
    vmStats.setLong(maxMemoryId, rt.maxMemory());

    // Compute processCpuTime separately, if not accessible ignore
    try {
      if (getProcessCpuTime != null) {
        Object v = getProcessCpuTime.invoke(osBean);
        vmStats.setLong(processCpuTimeId, (Long) v);
      }
    } catch (VirtualMachineError err) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error. We're poisoned
      // now, so don't let this thread continue.
      throw err;
    } catch (Throwable ex) {
      // Whenever you catch Error or Throwable, you must also
      // catch VirtualMachineError (see above). However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
    }

    if (unixBean != null) {
      try {
        Object v = getMaxFileDescriptorCount.invoke(unixBean);
        vmStats.setLong(unix_fdLimitId, (Long) v);
        v = getOpenFileDescriptorCount.invoke(unixBean);
        vmStats.setLong(unix_fdsOpenId, (Long) v);
      } catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable ex) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
      }
    }

    refresh(heapMemStats, getHeapMemoryUsage(memBean));
    refresh(nonHeapMemStats, getNonHeapMemoryUsage(memBean));
    refreshGC();
    refreshMemoryPools();
    bufferPoolStats.refresh();
    refreshThreads();
  }

  /**
   * Handle JDK-8207200 gracefully while fetching getHeapMemoryUsage from MemoryMXBean.
   *
   * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8207200">JDK-8207200</a>
   */
  private MemoryUsage getHeapMemoryUsage(MemoryMXBean memBean) {
    try {
      return memBean.getHeapMemoryUsage();
    } catch (IllegalArgumentException e) {
      if (logger.isDebugEnabled()) {
        logger.debug("JDK-8207200 prevented stat sampling for HeapMemoryUsage");
      }
      return null;
    }
  }

  /**
   * Handle JDK-8207200 gracefully while fetching getNonHeapMemoryUsage from MemoryMXBean.
   *
   * @see <a href="https://bugs.openjdk.java.net/browse/JDK-8207200">JDK-8207200</a>
   */
  private MemoryUsage getNonHeapMemoryUsage(MemoryMXBean memBean) {
    try {
      return memBean.getNonHeapMemoryUsage();
    } catch (IllegalArgumentException e) {
      if (logger.isDebugEnabled()) {
        logger.debug("JDK-8207200 prevented stat sampling for NonHeapMemoryUsage");
      }
      return null;
    }
  }

  private void refresh(Statistics stats, MemoryUsage mu) {
    if (mu == null) {
      return;
    }
    stats.setLong(mu_initMemoryId, mu.getInit());
    stats.setLong(mu_usedMemoryId, mu.getUsed());
    stats.setLong(mu_committedMemoryId, mu.getCommitted());
    stats.setLong(mu_maxMemoryId, mu.getMax());
  }

  @Override
  public void close() {
    heapMemStats.close();
    nonHeapMemStats.close();
    vmStats.close();
    closeStatsMap(mpMap);
    closeStatsMap(gcMap);
    bufferPoolStats.close();
  }

  private void closeStatsMap(Map<?, Statistics> map) {
    for (Statistics s : map.values()) {
      s.close();
    }
  }

  private static class ThreadStatInfo {
    public ThreadInfo ti;
    public final Statistics s;

    public ThreadStatInfo(ThreadInfo ti, Statistics s) {
      this.ti = ti;
      this.s = s;
    }
  }

  public static StatisticsType getType() {
    return vmType;
  }

  public Statistics getVMStats() {
    return vmStats;
  }

  public Statistics getVMHeapStats() {
    return heapMemStats;
  }

  public static StatisticsType getGCType() {
    return gcType;
  }

}
