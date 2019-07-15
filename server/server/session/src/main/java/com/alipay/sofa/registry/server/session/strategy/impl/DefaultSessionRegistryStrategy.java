/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.strategy.impl;

import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.common.model.store.Watcher;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.cache.CacheService;
import com.alipay.sofa.registry.server.session.cache.DatumKey;
import com.alipay.sofa.registry.server.session.cache.Key;
import com.alipay.sofa.registry.server.session.store.Interests;
import com.alipay.sofa.registry.server.session.strategy.SessionRegistryStrategy;
import com.alipay.sofa.registry.task.listener.TaskEvent;
import com.alipay.sofa.registry.task.listener.TaskListenerManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author xuanbei
 * @since 2019/2/15
 */
public class DefaultSessionRegistryStrategy implements SessionRegistryStrategy {
    private static final Logger LOGGER         = LoggerFactory
                                                   .getLogger(DefaultSessionRegistryStrategy.class);

    private static final Logger CONSOLE_LOGGER = LoggerFactory.getLogger("SESSION-CONSOLE",
                                                   "[Task]");

    private static final Logger taskLogger     = LoggerFactory.getLogger(
                                                   DefaultSessionRegistryStrategy.class, "[Task]");

    /**
     * store subscribers
     */
    @Autowired
    private Interests           sessionInterests;

    /**
     * trigger task com.alipay.sofa.registry.server.meta.listener process
     */
    @Autowired
    private TaskListenerManager taskListenerManager;

    @Autowired
    private SessionServerConfig sessionServerConfig;

    @Autowired
    private CacheService        sessionCacheService;

    @Override
    public void doFetchChangDataProcess(Map<String/*datacenter*/, Map<String/*datainfoid*/, Long>> dataInfoIdVersions) {
        //diff dataCenter same dataInfoId sent once fetch on cloud mode
        // fan: 只针对dataInfoId拉取数据，本次拉取会将dataInfoId在各个data center的地址数据都拉取回来
        Set<String> changeDataInfoIds = new HashSet<>();
        dataInfoIdVersions.forEach((dataCenter, dataInfoIdMap) -> {
            if (dataInfoIdMap != null) {
                dataInfoIdMap.forEach((dataInfoID, version) -> {
                    if (checkInterestVersions(dataCenter, dataInfoID, version)) {

                        //update cache
                        sessionCacheService.invalidate(new Key(
                                Key.KeyType.OBJ, DatumKey.class.getName(), new DatumKey(dataInfoID, dataCenter)));

                        changeDataInfoIds.add(dataInfoID);
                    }
                });
            }
        });

        changeDataInfoIds.forEach(this::fireDataChangeCloudTask);
    }

    private boolean checkInterestVersions(String dataCenter, String dataInfoId, Long version) {
        boolean result = sessionInterests.checkInterestVersions(dataCenter, dataInfoId, version);
        if (result) {
            LOGGER
                .info(
                    "Request dataCenter {} dataInfo {} fetch version {} be interested,Higher than current version!Will fire data change Task",
                    dataCenter, dataInfoId, version);
        }
        return result;
    }

    private void fireDataChangeCloudTask(String dataInfoId) {
        //trigger fetch data for subscriber,and push to client node
        TaskEvent taskEvent = new TaskEvent(dataInfoId,
            TaskEvent.TaskType.DATA_CHANGE_FETCH_CLOUD_TASK);
        taskLogger.info("send " + taskEvent.getTaskType() + " taskEvent:{}", taskEvent);
        taskListenerManager.sendTaskEvent(taskEvent);
    }

    @Override
    public void afterPublisherRegister(Publisher publisher) {
        TaskEvent syncPublisherTask = new TaskEvent(publisher,
            TaskEvent.TaskType.SYNC_PUBLISHER_TASK);
        CONSOLE_LOGGER.info("send " + syncPublisherTask.getTaskType() + " taskEvent:{}", publisher);
        taskListenerManager.sendTaskEvent(syncPublisherTask);
    }

    @Override
    public void afterSubscriberRegister(Subscriber subscriber) {
        if (!sessionServerConfig.isStopPushSwitch()) {
            //trigger fetch data for subscriber,and push to client node
            TaskEvent taskEvent = new TaskEvent(subscriber,
                TaskEvent.TaskType.SUBSCRIBER_REGISTER_FETCH_TASK);
            taskLogger.info("send " + taskEvent.getTaskType() + " taskEvent:{}", taskEvent);
            taskListenerManager.sendTaskEvent(taskEvent);
        }
    }

    @Override
    public void afterWatcherRegister(Watcher watcher) {
        fireWatcherRegisterFetchTask(watcher);
    }

    @Override
    public void afterPublisherUnRegister(Publisher publisher) {

    }

    @Override
    public void afterSubscriberUnRegister(Subscriber subscriber) {

    }

    @Override
    public void afterWatcherUnRegister(Watcher watcher) {

    }

    private void fireWatcherRegisterFetchTask(Watcher watcher) {
        //trigger fetch data for watcher,and push to client node
        TaskEvent taskEvent = new TaskEvent(watcher, TaskEvent.TaskType.WATCHER_REGISTER_FETCH_TASK);
        taskLogger.info("send " + taskEvent.getTaskType() + " taskEvent:{}", taskEvent);
        taskListenerManager.sendTaskEvent(taskEvent);
    }
}
