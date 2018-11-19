package me.itzg.tryetcdworkpart.services;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

import com.coreos.jetcd.Client;
import io.etcd.jetcd.launcher.junit.EtcdClusterResource;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import me.itzg.tryetcdworkpart.WorkProcessor;
import me.itzg.tryetcdworkpart.config.WorkerProperties;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RunWith(MockitoJUnitRunner.class)
public class WorkAllocatorTest {

  @Mock
  private WorkProcessor workProcessor;

  @Rule
  public final EtcdClusterResource etcd = new EtcdClusterResource("test-etcd", 1);

  @Rule
  public TestName testName = new TestName();

  private ThreadPoolTaskExecutor taskExecutor;
  private WorkerProperties workerProperties;

  @Before
  public void setUp() throws Exception {
    taskExecutor = new ThreadPoolTaskExecutor();

    workerProperties = new WorkerProperties();
    workerProperties.setPrefix("/"+testName.getMethodName()+"/");
  }

  @Test
  public void testSingleWorkItemSingleWorker() {
    final List<String> endpoints = etcd.cluster().getClientEndpoints().stream()
        .map(URI::toString)
        .collect(Collectors.toList());
    Client client = Client.builder().endpoints(endpoints).build();


    final WorkAllocator workAllocator = new WorkAllocator(
        workerProperties, client, workProcessor, taskExecutor);
    workAllocator.start();

    workAllocator.createWork("testing=one")
    .join();

    verify(workProcessor, after(5000).atLeastOnce())
        .start(anyString(), eq("testing=one"));
  }
}