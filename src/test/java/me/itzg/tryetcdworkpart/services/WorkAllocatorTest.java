package me.itzg.tryetcdworkpart.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.coreos.jetcd.Client;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.options.GetOption;
import io.etcd.jetcd.launcher.junit.EtcdClusterResource;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import me.itzg.tryetcdworkpart.Bits;
import me.itzg.tryetcdworkpart.Work;
import me.itzg.tryetcdworkpart.WorkProcessor;
import me.itzg.tryetcdworkpart.config.WorkerProperties;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RunWith(MockitoJUnitRunner.class)
@Slf4j
public class WorkAllocatorTest {

  @Mock
  private WorkProcessor workProcessor;

  @Rule
  public final EtcdClusterResource etcd = new EtcdClusterResource("test-etcd", 1);

  @Rule
  public TestName testName = new TestName();

  private ThreadPoolTaskExecutor taskExecutor;
  private WorkerProperties workerProperties;
  private Client client;

  @Before
  public void setUp() throws Exception {
    taskExecutor = new ThreadPoolTaskExecutor();
    taskExecutor.setQueueCapacity(0);
    taskExecutor.setThreadNamePrefix("watchers-");
    taskExecutor.initialize();

    workerProperties = new WorkerProperties();
    workerProperties.setPrefix("/"+testName.getMethodName()+"/");

    final List<String> endpoints = etcd.cluster().getClientEndpoints().stream()
        .map(URI::toString)
        .collect(Collectors.toList());
    client = Client.builder().endpoints(endpoints).build();
  }

  @After
  public void tearDown() throws Exception {
    taskExecutor.shutdown();
    client.close();
  }

  @Test
  public void testSingleWorkItemSingleWorker() {
    final WorkAllocator workAllocator = new WorkAllocator(
        workerProperties, client, workProcessor, taskExecutor);
    workAllocator.start();

    workAllocator.createWork("testing=one")
    .join();

    verify(workProcessor, timeout(5000).atLeastOnce())
        .start(anyString(), eq("testing=one"));
    verifyNoMoreInteractions(workProcessor);
  }

  @Test
  public void testOneWorkerExistingItems() throws InterruptedException, ExecutionException {
    final int totalWorkItems = 5;

    final BulkWorkProcessor bulkWorkProcessor = new BulkWorkProcessor(totalWorkItems);

    final WorkAllocator workAllocator = new WorkAllocator(
        workerProperties, client, bulkWorkProcessor, taskExecutor);

    for (int i = 0; i < totalWorkItems; i++) {
      workAllocator.createWork(String.format("%d", i));
    }

    workAllocator.start();

    try {
      bulkWorkProcessor.hasActiveWorkItems(totalWorkItems, 2000);

      assertWorkLoad(totalWorkItems, workAllocator.getId());
    } finally {
      workAllocator.stop();
    }
  }

  @Test
  public void testOneWorkerWithItemDeletion() throws InterruptedException, ExecutionException {
    final int totalWorkItems = 5;

    final BulkWorkProcessor bulkWorkProcessor = new BulkWorkProcessor(totalWorkItems);

    final WorkAllocator workAllocator = new WorkAllocator(
        workerProperties, client, bulkWorkProcessor, taskExecutor);

    final List<Work> createdWork = new ArrayList<>();
    for (int i = 0; i < totalWorkItems; i++) {
      createdWork.add(
        workAllocator.createWork(String.format("%d", i)).get()
      );
    }

    workAllocator.start();

    try {
      bulkWorkProcessor.hasActiveWorkItems(totalWorkItems, 2000);
      assertWorkLoad(totalWorkItems, workAllocator.getId());

      workAllocator.deleteWork(createdWork.get(0).getId());
      bulkWorkProcessor.hasActiveWorkItems(totalWorkItems-1, 2000);
      assertWorkLoad(totalWorkItems-1, workAllocator.getId());

    } finally {
      workAllocator.stop();
    }
  }

  private void assertWorkLoad(int expected, String workAllocatorId)
      throws ExecutionException, InterruptedException {
    final int actualWorkLoad = client.getKVClient()
        .get(
            ByteSequence
                .fromString(workerProperties.getPrefix() + Bits.WORKERS_SET + workAllocatorId)
        )
        .thenApply(getResponse -> {
          final int actual = Integer
              .parseInt(getResponse.getKvs().get(0).getValue().toStringUtf8(), 10);
          return actual;
        })
        .get();

    assertEquals(expected, actualWorkLoad);
  }

  @Test
  public void testManyWorkersMoreWorkItems() throws InterruptedException, ExecutionException {
    final int totalWorkers = 5;
    final int totalWorkItems = 40;

    final BulkWorkProcessor bulkWorkProcessor = new BulkWorkProcessor(totalWorkItems);

    final List<WorkAllocator> workAllocators = IntStream.range(0, totalWorkers)
        .mapToObj(index -> new WorkAllocator(workerProperties, client, bulkWorkProcessor, taskExecutor))
        .collect(Collectors.toList());

    for (WorkAllocator workAllocator : workAllocators) {
      workAllocator.start();
    }
    // allow them to start and settle
    Thread.sleep(1000);

    final List<Work> createdWork = new ArrayList<>();
    for (int i = 0; i < totalWorkItems; i++) {
      createdWork.add(
          workAllocators.get(0).createWork(String.format("%d", i)).get()
      );
    }

    bulkWorkProcessor.hasEnoughStarts(totalWorkItems, 10000);

    bulkWorkProcessor.hasActiveWorkItems(totalWorkItems, 10000);

    final ByteSequence activePrefix = ByteSequence
        .fromString(workerProperties.getPrefix() + Bits.ACTIVE_SET);
    final WorkItemSummary workItemSummary = client.getKVClient()
        .get(
            activePrefix,
            GetOption.newBuilder()
                .withPrefix(activePrefix)
                .build()
        )
        .thenApply(getResponse -> {
          final WorkItemSummary result = new WorkItemSummary();
          for (KeyValue kv : getResponse.getKvs()) {
            result.activeWorkIds.add(Bits.extractIdFromKey(kv));
            final String workerId = kv.getValue().toStringUtf8();
            int load = result.workerLoad.getOrDefault(workerId, 0);
            result.workerLoad.put(workerId, load + 1);
          }
          return result;
        })
        .get();

    assertThat(workItemSummary.activeWorkIds, Matchers.hasSize(totalWorkItems));
    //
    workItemSummary.workerLoad.forEach((workerId, load) -> {
      log.info("Worker={} has load={}", workerId, load);
    });

    Thread.sleep(1000);
    for (WorkAllocator workAllocator : workAllocators) {
      workAllocator.stop();
    }
  }

  static class WorkItemSummary {
    HashSet<String> activeWorkIds = new HashSet<>();
    Map<String, Integer> workerLoad = new HashMap<>();
  }

  private static class BulkWorkProcessor implements WorkProcessor {

    final Lock lock = new ReentrantLock();
    final Condition hasEnoughStarts = lock.newCondition();
    final Set<Integer> observedStarts;
    int activeWorkItems = 0;
    final Condition activeItemsCondition = lock.newCondition();

    public BulkWorkProcessor(int expectedWorkItems) {
      observedStarts = new HashSet<>(expectedWorkItems);
    }

    @Override
    public void start(String id, String content) {
      final int workItemIndex = Integer.parseInt(content);

      lock.lock();
      ++activeWorkItems;
      activeItemsCondition.signal();
      try {
        if (observedStarts.add(workItemIndex)) {
          hasEnoughStarts.signal();
        }
      } finally {
        lock.unlock();
      }
    }

    public void hasEnoughStarts(int expectedWorkItems, long timeout) throws InterruptedException {
      lock.lock();
      try {
        while (observedStarts.size() < expectedWorkItems) {
          hasEnoughStarts.await(timeout, TimeUnit.MILLISECONDS);
        }
      } finally {
        lock.unlock();
      }
    }

    public void hasActiveWorkItems(int expected, long timeout) throws InterruptedException {
      lock.lock();
      try {
        while (activeWorkItems != expected) {
          activeItemsCondition.await(timeout, TimeUnit.MILLISECONDS);
        }
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void update(String id, String content) {
      // ignore for now
    }

    @Override
    public void stop(String id, String content) {
      lock.lock();
      try {
        --activeWorkItems;
        activeItemsCondition.signal();
      } finally {
        lock.unlock();
      }
    }
  }
}