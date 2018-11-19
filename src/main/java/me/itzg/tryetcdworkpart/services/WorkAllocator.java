package me.itzg.tryetcdworkpart.services;

import static com.coreos.jetcd.data.ByteSequence.fromString;
import static com.coreos.jetcd.op.Op.put;
import static me.itzg.tryetcdworkpart.Bits.ACTIVE_SET;
import static me.itzg.tryetcdworkpart.Bits.REGISTRY_SET;
import static me.itzg.tryetcdworkpart.Bits.WORKERS_SET;
import static me.itzg.tryetcdworkpart.Bits.WORK_LOAD_FORMAT;
import static me.itzg.tryetcdworkpart.Bits.extractIdFromKey;
import static me.itzg.tryetcdworkpart.Bits.fromFormat;
import static me.itzg.tryetcdworkpart.Bits.isDeleteKeyEvent;
import static me.itzg.tryetcdworkpart.Bits.isNewKeyEvent;
import static me.itzg.tryetcdworkpart.Bits.isUpdateKeyEvent;

import com.coreos.jetcd.Client;
import com.coreos.jetcd.Watch.Watcher;
import com.coreos.jetcd.common.exception.ClosedClientException;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.kv.DeleteResponse;
import com.coreos.jetcd.kv.GetResponse;
import com.coreos.jetcd.op.Cmp;
import com.coreos.jetcd.op.CmpTarget;
import com.coreos.jetcd.op.Op;
import com.coreos.jetcd.options.DeleteOption;
import com.coreos.jetcd.options.GetOption;
import com.coreos.jetcd.options.GetOption.SortOrder;
import com.coreos.jetcd.options.GetOption.SortTarget;
import com.coreos.jetcd.options.PutOption;
import com.coreos.jetcd.options.WatchOption;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import me.itzg.tryetcdworkpart.Bits;
import me.itzg.tryetcdworkpart.Work;
import me.itzg.tryetcdworkpart.WorkProcessor;
import me.itzg.tryetcdworkpart.config.WorkerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WorkAllocator implements SmartLifecycle {

  private final WorkerProperties properties;
  private final Client etcd;
  private final WorkProcessor processor;
  private final ThreadPoolTaskExecutor executorService;
  private final String prefix;
  private String ourId;
  private long leaseId;
  private AtomicInteger workLoad = new AtomicInteger();
  private boolean running;
  private Deque<String> ourWork = new ConcurrentLinkedDeque<>();

  @Autowired
  public WorkAllocator(WorkerProperties properties, Client etcd, WorkProcessor processor,
      ThreadPoolTaskExecutor executorService) {
    this.properties = properties;
    this.etcd = etcd;
    this.processor = processor;
    this.executorService = executorService;

    this.prefix = properties.getPrefix().endsWith("/") ?
        properties.getPrefix() :
        properties.getPrefix() + "/";

    log.info("Using prefix={}", this.prefix);
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public void start() {
    ourId = UUID.randomUUID().toString();
    log.info("I am worker={}", ourId);

    running = true;

    etcd.getLeaseClient()
        .grant(properties.getLeaseDuration().getSeconds())
        .thenApply(leaseGrantResponse -> {
          leaseId = leaseGrantResponse.getID();
          log.info("Got lease={}", ourId, leaseId);
          etcd.getLeaseClient().keepAlive(leaseId);
          return leaseId;
        })
        .thenAccept(leaseId ->
            initOurWorkerEntry()
                .thenAccept(ignored -> {
                  log.info("Starting up watchers");
                  watchRegistry();
                  watchActive();
                  watchWorkers();
                }))
        .join();
  }

  @Override
  public void stop() {
    stop(() -> {
    });
  }

  @Override
  public void stop(Runnable callback) {
    running = false;
    etcd.getLeaseClient()
        .revoke(leaseId)
        .thenAccept(leaseRevokeResponse -> {
          callback.run();
        });
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void buildWatcher(String prefix,
      long revision, Consumer<WatchResponse> watchResponseConsumer) {
    final ByteSequence prefixBytes = fromString(prefix);
    final Watcher watcher = etcd.getWatchClient()
        .watch(
            prefixBytes,
            WatchOption.newBuilder()
                .withRevision(revision)
                .withPrefix(prefixBytes)
                .withPrevKV(true)
                .build()
        );

    executorService.submit(() -> {
      log.info("Watching {}", prefix);
      while (running) {
        try {
          final WatchResponse watchResponse = watcher.listen();
          if (running) {
            watchResponseConsumer.accept(watchResponse);
          }
        } catch (ClosedClientException e) {
          log.info("Stopping watching of {}", prefix);
          return;
        } catch (InterruptedException e) {
          log.info("Interrupted while watching {}", prefix);
        } catch (Exception e) {
          log.warn("Failed while watching {}", prefix , e);
          return;
        }
      }
      log.info("Finished watching {}", prefix);
    });
  }

  public CompletableFuture<Work> createWork(String content) {
    final String id = UUID.randomUUID().toString();

    return etcd.getKVClient()
        .put(
            ByteSequence.fromString(prefix+REGISTRY_SET +id),
            ByteSequence.fromString(content)
        )
        .thenApply(putResponse ->
            new Work()
                .setId(id)
                .setContent(content)
                .setUpdated(putResponse.hasPrevKv())
        );
  }

  public CompletableFuture<Work> updateWork(String id, String content) {
    return etcd.getKVClient()
        .put(
            ByteSequence.fromString(prefix+REGISTRY_SET +id),
            ByteSequence.fromString(content)
        )
        .thenApply(putResponse ->
            new Work()
                .setId(id)
                .setContent(content)
                .setUpdated(putResponse.hasPrevKv())
        );
  }

  /**
   *
   * @param id the work item to delete
   * @return a {@link CompletableFuture} of the number of work items successfully deleted, usually 1
   */
  public CompletableFuture<Long> deleteWork(String id) {
    return etcd.getKVClient()
        .delete(
            ByteSequence.fromString(prefix+REGISTRY_SET +id)
        )
        .thenApply(DeleteResponse::getDeleted);
  }

  private void watchActive() {
    buildWatcher(prefix + ACTIVE_SET, 0, watchResponse -> {
      log.debug("Saw active={}", watchResponse);

      for (WatchEvent event : watchResponse.getEvents()) {
        if (Bits.isDeleteKeyEvent(event)) {
          final KeyValue kv = event.getPrevKV();
          handleReadyWork(WorkTransition.RELEASED, kv);
        }
      }
    });
  }

  private void watchRegistry() {
    etcd.getKVClient()
        .get(
            fromString(prefix + REGISTRY_SET),
            GetOption.newBuilder()
                .withPrefix(fromString(prefix + REGISTRY_SET))
                .build()
        )
        .thenAccept((getResponse) -> {
          log.debug("Initial registry response={}", getResponse);

          for (KeyValue kv : getResponse.getKvs()) {
            handleReadyWork(WorkTransition.STARTUP, kv);
          }

          buildWatcher(
              prefix + REGISTRY_SET,
              getResponse.getHeader().getRevision(),
              watchResponse -> {
                log.debug("Saw registry event={}", watchResponse);

                for (WatchEvent event : watchResponse.getEvents()) {
                  if (isNewKeyEvent(event)) {
                    handleReadyWork(WorkTransition.NEW, event.getKeyValue());
                  } else if (isUpdateKeyEvent(event)) {
                    handleRegisteredWorkUpdate(event.getKeyValue());
                  } else if (isDeleteKeyEvent(event)) {
                    handleRegisteredWorkDeletion(event.getPrevKV());
                  }
                }
              });
        });
  }

  private void handleRegisteredWorkUpdate(KeyValue kv) {
    final String workId = extractIdFromKey(kv);

    if (ourWork.contains(workId)) {
      log.info("Updated our work={}", workId);
      processor.update(workId, kv.getValue().toStringUtf8());
    }
  }

  private void handleRegisteredWorkDeletion(KeyValue kv) {
    final String workId = extractIdFromKey(kv);

    if (ourWork.remove(workId)) {
      log.info("Stopping our work={}", workId);
      processor.stop(workId, kv.getValue().toStringUtf8());
    }

    // Checking etcd content is purposely separate from the tracking in ourWork to reconcile
    // any divergence between our own state and etcd's state.
    final ByteSequence activeKeyBytes = fromString(prefix + ACTIVE_SET + workId);
    final ByteSequence ourIdBytes = fromString(ourId);
    etcd.getKVClient().txn()
        // if the active work item is ours
        .If(new Cmp(activeKeyBytes, Cmp.Op.EQUAL, CmpTarget.value(ourIdBytes)))
        // ...then delete it
        .Then(
            Op.delete(activeKeyBytes, DeleteOption.DEFAULT)
        )
        .commit()
        .thenAccept(txnResponse -> {
          if (txnResponse.isSucceeded()) {
            log.info("Removed active work={} key", workId);
            decWorkLoad();
          } else {
            log.info("Active work={} key was not present or not ours", workId);
            rebalanceWorkLoad();
          }
        });
  }

  private void watchWorkers() {
    buildWatcher(prefix + WORKERS_SET, 0, watchResponse -> {
      log.debug("Saw worker={}", watchResponse);

      boolean rebalance = false;
      for (WatchEvent event : watchResponse.getEvents()) {
        if (isNewKeyEvent(event)) {
          log.info("Saw new worker={}", Bits.extractIdFromKey(event.getKeyValue()));
          rebalance = true;
        }
      }
      if (rebalance) {
        rebalanceWorkLoad();
      }
    });
  }

  private CompletableFuture<?> rebalanceWorkLoad() {
    log.info("Rebalancing work load");

    return getTargetWorkload()
        .thenAccept(targetWorkload -> {
          long amountToShed = workLoad.get() - targetWorkload;
          if (amountToShed > 0) {
            log.info("Shedding work to rebalance count={}", amountToShed);
            for (; amountToShed > 0; --amountToShed) {

              // give preference to shedding most recently assigned work items with the theory
              // that we'll minimize churn of long held work items
              final String workIdToShed = ourWork.pop();
              releaseWork(workIdToShed);
            }
          }
        });
  }

  private CompletableFuture<Long> getTargetWorkload() {
    return getCountAtPrefix(prefix + WORKERS_SET)
        .thenCompose(workersCount ->
            getCountAtPrefix(prefix + REGISTRY_SET)
                .thenApply(workCount -> workCount / workersCount));
  }

  private void releaseWork(String workIdToShed) {
    log.info("Releasing work={}", workIdToShed);

    // tell processor to stop
    getWorkContent(workIdToShed)
        .thenAccept(content -> {
          processor.stop(workIdToShed, content);
        })

        // release the active work item
        .thenCompose(aVoid -> {
          final ByteSequence activeKeyBytes = fromString(prefix + ACTIVE_SET + workIdToShed);
          return etcd.getKVClient()
              .delete(activeKeyBytes);
        })

        // adjust our work load
        .thenCompose(deleteResponse -> decWorkLoad())
        .exceptionally(throwable -> {
          log.warn("Failure while releasing work={}", workIdToShed, throwable);
          return null;
        });
  }

  private CompletableFuture<Long> getCountAtPrefix(String prefix) {
    final ByteSequence prefixBytes = fromString(prefix);
    return etcd.getKVClient()
        .get(
            prefixBytes,
            GetOption.newBuilder()
                .withCountOnly(true)
                .withPrefix(prefixBytes)
                .build()
        )
        .thenApply(GetResponse::getCount);
  }

  private void handleReadyWork(WorkTransition transition, KeyValue kv) {
    final String workId = Bits.extractIdFromKey(kv);
    log.info("Handling potential readyWork={} due to transition={}", workId, transition);

    amILeastLoaded()
        .thenAccept(leastLoaded -> {
          if (leastLoaded) {
            log.info("I am least loaded, so I'll try to grab work={}", workId);
            // NOTE: we can't pass the value from kv here since we might have only seen
            // an active entry deletion where all we know is workId
            grabWork(workId);
          }
        });
  }

  private void grabWork(String workId) {
    final ByteSequence activeKey = fromString(prefix + ACTIVE_SET + workId);
    final ByteSequence registryKey = fromString(prefix + REGISTRY_SET + workId);
    final ByteSequence ourValue = fromString(ourId);

    etcd.getKVClient().txn()
        // if not created yet under active list
        .If(
            // check if nobody else grabbed it
            new Cmp(activeKey, Cmp.Op.EQUAL, CmpTarget.version(0)),
            // but also check it wasn't also removed from work registry
            new Cmp(registryKey, Cmp.Op.GREATER, CmpTarget.version(0))
        )
        .Then(put(
            activeKey,
            ourValue,
            PutOption.newBuilder()
                .withLeaseId(leaseId)
                .build()
            )
        )
        .commit()
        .thenAccept(txnResponse -> {
          log.debug("Result of grab txn = {}", txnResponse);

          if (txnResponse.isSucceeded()) {
            log.info("Successfully grabbed work={}", workId);
            incWorkLoad()
                .thenCompose(newWorkLoad ->
                    getWorkContent(workId)
                        .thenAccept(content -> handleGrabbedWork(workId, content))
                )
                .exceptionally(throwable -> {
                  log.warn("Failed to get work={} content", workId, throwable);
                  return null;
                });
          }
        });
  }

  private void handleGrabbedWork(String workId, String content) {
    ourWork.push(workId);
    processor.start(workId, content);
  }

  private CompletableFuture<String> getWorkContent(String workId) {
    return etcd.getKVClient()
        .get(
            fromString(prefix + REGISTRY_SET + workId)
        )
        .thenApply(getResponse -> getResponse.getKvs().get(0).getValue().toStringUtf8());
  }

  private CompletableFuture<Boolean> amILeastLoaded() {
    // Because of the zero-padded formatting of the work load value stored in each worker entry,
    // we can find the least loaded worker via etcd by doing an ASCII sort of the values and picking
    // off the lowest value.
    return etcd.getKVClient()
        .get(
            fromString(prefix + WORKERS_SET),
            GetOption.newBuilder()
                .withPrefix(fromString(prefix + WORKERS_SET))
                .withSortField(SortTarget.VALUE)
                .withSortOrder(SortOrder.ASCEND)
                .withLimit(1)
                .build()
        )
        .thenApply(getResponse -> {
          if (getResponse.getCount() <= 1) {
            log.info("I'm the only worker");
            // it's only us, so we're it
            return true;
          }

          // see if we're the least loaded of the current works
          final KeyValue kv = getResponse.getKvs().get(0);
          final String leastLoadedId = Bits.extractIdFromKey(kv);
          final boolean leastLoaded = ourId.equals(leastLoadedId);
          log.info(
              "I am leastLoaded={} out of workerCount={}",
              leastLoaded, getResponse.getCount());
          return leastLoaded;
        });
  }

  private CompletableFuture<?> initOurWorkerEntry() {
    return etcd.getKVClient()
        .put(
            fromString(prefix + WORKERS_SET + ourId),
            Bits.fromFormat(WORK_LOAD_FORMAT, 0),
            PutOption.newBuilder()
                .withLeaseId(leaseId)
                .build()
        );
  }

  private CompletableFuture<?> incWorkLoad() {
    final int newWorkLoad = workLoad.incrementAndGet();

    return storeWorkload(newWorkLoad - 1, newWorkLoad);
  }

  private CompletableFuture<?> decWorkLoad() {
    final int newWorkLoad = workLoad.decrementAndGet();

    return storeWorkload(newWorkLoad + 1, newWorkLoad);
  }

  /**
   * @return a completable of the 'to' work load
   */
  private CompletableFuture<Integer> storeWorkload(int from, int to) {
    final ByteSequence ourWorkerKeyBytes = fromString(prefix + WORKERS_SET + ourId);
    final ByteSequence fromBytes = fromFormat(WORK_LOAD_FORMAT, from);
    final ByteSequence toBytes = fromFormat(WORK_LOAD_FORMAT, to);

    return etcd.getKVClient().txn()
        .If(new Cmp(
            ourWorkerKeyBytes,
            Cmp.Op.EQUAL,
            CmpTarget.value(fromBytes)
        ))
        .Then(Op.put(
            ourWorkerKeyBytes,
            toBytes,
            PutOption.newBuilder()
                .withLeaseId(leaseId)
                .build()
        ))
        .commit()
        .thenComposeAsync(resp -> {
          if (resp.isSucceeded()) {
            log.info("Stored workLoad={} update", to);
            return CompletableFuture.completedFuture(to);
          } else {
            log.debug("Missed update txn, trying again from={}, to={}", from, to);
            return storeWorkload(from, to);
          }
        });
  }

  private enum WorkTransition {
    STARTUP,
    NEW,
    RELEASED
  }

}
