/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eagle.jpm.mr.running;

import com.typesafe.config.ConfigFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.curator.utils.CloseableUtils;
import org.apache.eagle.jpm.mr.running.recover.MRRunningJobManager;
import org.apache.eagle.jpm.util.jobrecover.RunningJobManager;
import org.apache.zookeeper.CreateMode;
import org.junit.*;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.mockStatic;


@RunWith(PowerMockRunner.class)
@PrepareForTest({MRRunningJobManager.class, RunningJobManager.class, LoggerFactory.class})
@PowerMockIgnore({"javax.*"})
public class MRRunningJobManagerTest {
    private static TestingServer zk;
    private static com.typesafe.config.Config config = ConfigFactory.load();
    private static CuratorFramework curator;
    private static final String SHARE_RESOURCES = "/apps/mr/running/sandbox/yarnAppId/jobId";
    private static final int QTY = 5;
    private static final int REPETITIONS = QTY * 10;
    private static MRRunningJobConfig.EndpointConfig endpointConfig;
    private static MRRunningJobConfig.ZKStateConfig zkStateConfig;
    private static org.slf4j.Logger log = mock(org.slf4j.Logger.class);
    private static final int BUFFER_SIZE = 4096;
    private static final String LOCKS_BASE_PATH = "/locks";

    @BeforeClass
    public static void setupZookeeper() throws Exception {
        zk = new TestingServer();
        curator = CuratorFrameworkFactory.newClient(zk.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        MRRunningJobConfig mrRunningJobConfig = MRRunningJobConfig.newInstance(config);
        zkStateConfig = mrRunningJobConfig.getZkStateConfig();
        zkStateConfig.zkQuorum = zk.getConnectString();
        endpointConfig = mrRunningJobConfig.getEndpointConfig();
        mockStatic(LoggerFactory.class);
        when(LoggerFactory.getLogger(any(Class.class))).thenReturn(log);
    }

    @AfterClass
    public static void teardownZookeeper() throws Exception {
        CloseableUtils.closeQuietly(curator);
        CloseableUtils.closeQuietly(zk);
    }

    @Before
    public void createPath() throws Exception {
        if(curator.checkExists().forPath(SHARE_RESOURCES) == null) {
            curator.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(SHARE_RESOURCES);
        }
    }

    @After
    public void cleanPath() throws Exception {
        if (curator.checkExists().forPath(SHARE_RESOURCES) != null) {
            curator.delete().deletingChildrenIfNeeded().forPath(SHARE_RESOURCES);
        }
        if (curator.checkExists().forPath(LOCKS_BASE_PATH) != null) {
            curator.delete().guaranteed().deletingChildrenIfNeeded().forPath(LOCKS_BASE_PATH);
        }
    }


    @Test
    @Ignore
    public void testMRRunningJobManagerDelWithLock() throws Exception {
        Assert.assertTrue(curator.checkExists().forPath(SHARE_RESOURCES) != null);

        ExecutorService service = Executors.newFixedThreadPool(QTY);
        for (int i = 0; i < QTY; ++i) {
            Callable<Void> task = () -> {
                try {
                    MRRunningJobManager mrRunningJobManager = new MRRunningJobManager(zkStateConfig);
                    for (int j = 0; j < REPETITIONS; ++j) {
                        mrRunningJobManager.delete("yarnAppId", "jobId");
                    }
                } catch (Exception e) {
                    // log or do something
                }
                return null;
            };
            service.submit(task);
        }

        service.shutdown();
        service.awaitTermination(10, TimeUnit.MINUTES);
        Assert.assertTrue(curator.checkExists().forPath(SHARE_RESOURCES) == null);
        verify(log, never()).error(anyString(), anyString(), anyString(), anyString(), any(Throwable.class));
        verify(log, never()).error(anyString(), anyString(), anyString());
        verify(log, never()).error(anyString(), any(Throwable.class));

    }

    @Test
    @Ignore
    public void testMRRunningJobManagerRecoverYarnAppWithLock() throws Exception {
        Assert.assertTrue(curator.checkExists().forPath(SHARE_RESOURCES) != null);
        curator.setData().forPath(SHARE_RESOURCES, generateZkSetData());
        ExecutorService service = Executors.newFixedThreadPool(QTY);
        for (int i = 0; i < QTY; ++i) {
            Callable<Void> task = () -> {
                try {
                    MRRunningJobManager mrRunningJobManager = new MRRunningJobManager(zkStateConfig);
                    for (int j = 0; j < REPETITIONS; ++j) {
                        if(j % 3 == 0) {
                            mrRunningJobManager.delete("yarnAppId", "jobId");
                        } else {
                            mrRunningJobManager.recoverYarnApp("yarnAppId");
                        }
                    }
                } catch (Exception e) {
                    // log or do something
                }
                return null;
            };
            service.submit(task);
        }

        service.shutdown();
        service.awaitTermination(10, TimeUnit.MINUTES);
        verify(log, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    public void testMRRunningJobManagerRecoverWithLock() throws Exception {
        Assert.assertTrue(curator.checkExists().forPath(SHARE_RESOURCES) != null);
        curator.setData().forPath(SHARE_RESOURCES, generateZkSetData());
        ExecutorService service = Executors.newFixedThreadPool(QTY);
        for (int i = 0; i < QTY; ++i) {
            Callable<Void> task = () -> {
                try {
                    MRRunningJobManager mrRunningJobManager = new MRRunningJobManager(zkStateConfig);
                    for (int j = 0; j < REPETITIONS; ++j) {
                        if(j % 3 == 0) {
                            mrRunningJobManager.delete("yarnAppId", "jobId");
                        } else {
                            mrRunningJobManager.recover();
                        }
                    }
                } catch (Exception e) {
                    // log or do something
                }
                return null;
            };
            service.submit(task);
        }

        service.shutdown();
        service.awaitTermination(10, TimeUnit.MINUTES);
        verify(log, never()).error(anyString(), any(Throwable.class));
    }

    private byte[] generateZkSetData() throws IOException {
        InputStream jsonstream = this.getClass().getResourceAsStream("/jobInfo_805.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] data = new byte[BUFFER_SIZE];
        int count = -1;
        while((count = jsonstream.read(data, 0, BUFFER_SIZE)) != -1) {
            outputStream.write(data, 0, count);
        }
        data = null;
        return outputStream.toByteArray();
    }

}
