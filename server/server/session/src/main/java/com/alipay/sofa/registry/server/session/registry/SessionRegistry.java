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
package com.alipay.sofa.registry.server.session.registry;

import com.alipay.sofa.registry.common.model.Node;
import com.alipay.sofa.registry.common.model.store.BaseInfo;
import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.common.model.store.StoreData;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.common.model.store.URL;
import com.alipay.sofa.registry.common.model.store.Watcher;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.remoting.Server;
import com.alipay.sofa.registry.remoting.exchange.Exchange;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.node.NodeManager;
import com.alipay.sofa.registry.server.session.node.service.DataNodeService;
import com.alipay.sofa.registry.server.session.store.DataStore;
import com.alipay.sofa.registry.server.session.store.Interests;
import com.alipay.sofa.registry.server.session.store.Watchers;
import com.alipay.sofa.registry.server.session.strategy.SessionRegistryStrategy;
import com.alipay.sofa.registry.task.listener.TaskEvent;
import com.alipay.sofa.registry.task.listener.TaskEvent.TaskType;
import com.alipay.sofa.registry.task.listener.TaskListenerManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author shangyu.wh
 * @version $Id: AbstractSessionRegistry.java, v 0.1 2017-11-30 18:13 shangyu.wh Exp $
 */
public class SessionRegistry implements Registry {

    private static final Logger     LOGGER      = LoggerFactory.getLogger(SessionRegistry.class);

    private static final Logger     TASK_LOGGER = LoggerFactory.getLogger(SessionRegistry.class,
                                                    "[Task]");

    /**
     * store subscribers
     */
    @Autowired
    private Interests               sessionInterests;

    /**
     * store watchers
     */
    @Autowired
    private Watchers                sessionWatchers;

    /**
     * store publishers
     */
    @Autowired
    private DataStore               sessionDataStore;

    /**
     * transfer data to DataNode
     */
    @Autowired
    private DataNodeService         dataNodeService;

    /**
     * trigger task com.alipay.sofa.registry.server.meta.listener process
     */
    @Autowired
    private TaskListenerManager     taskListenerManager;

    /**
     * calculate data node url
     */
    @Autowired
    private NodeManager             dataNodeManager;

    @Autowired
    private SessionServerConfig     sessionServerConfig;

    @Autowired
    private Exchange                boltExchange;

    @Autowired
    private SessionRegistryStrategy sessionRegistryStrategy;

    @Override
    public void register(StoreData storeData) {

        //check connect already existed
        checkConnect(storeData);

        switch (storeData.getDataType()) {
            case PUBLISHER:
                Publisher publisher = (Publisher) storeData;

                dataNodeService.register(publisher);

                sessionDataStore.add(publisher);

                sessionRegistryStrategy.afterPublisherRegister(publisher);
                break;
            case SUBSCRIBER:
                Subscriber subscriber = (Subscriber) storeData;

                sessionInterests.add(subscriber);

                sessionRegistryStrategy.afterSubscriberRegister(subscriber);
                break;
            case WATCHER:
                Watcher watcher = (Watcher) storeData;

                sessionWatchers.add(watcher);

                sessionRegistryStrategy.afterWatcherRegister(watcher);
                break;
            default:
                break;
        }

    }

    @Override
    public void unRegister(StoreData<String> storeData) {

        switch (storeData.getDataType()) {
            case PUBLISHER:
                Publisher publisher = (Publisher) storeData;

                sessionDataStore.deleteById(storeData.getId(), publisher.getDataInfoId());

                dataNodeService.unregister(publisher);

                sessionRegistryStrategy.afterPublisherUnRegister(publisher);
                break;

            case SUBSCRIBER:
                Subscriber subscriber = (Subscriber) storeData;
                sessionInterests.deleteById(storeData.getId(), subscriber.getDataInfoId());
                sessionRegistryStrategy.afterSubscriberUnRegister(subscriber);
                break;

            case WATCHER:
                Watcher watcher = (Watcher) storeData;

                sessionWatchers.deleteById(watcher.getId(), watcher.getDataInfoId());

                sessionRegistryStrategy.afterWatcherUnRegister(watcher);
                break;
            default:
                break;
        }

    }

    @Override
    public void cancel(List<String> connectIds) {
        TaskEvent taskEvent = new TaskEvent(connectIds, TaskType.CANCEL_DATA_TASK);
        TASK_LOGGER.info("send " + taskEvent.getTaskType() + " taskEvent:{}", taskEvent);
        taskListenerManager.sendTaskEvent(taskEvent);
    }

    @Override
    public void fetchChangData() {
        // isBeginDataFetchTask在connectMetaServer时，从meta server拉取了
        if (!sessionServerConfig.isBeginDataFetchTask()) {
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                LOGGER.error("fetchChangData task sleep InterruptedException", e);
            }
            return;
        }

        fetchChangDataProcess();
    }

    @Override
    public void fetchChangDataProcess() {

        //check dataInfoId's sub list is not empty
        List<String> checkDataInfoIds = new ArrayList<>();
        sessionInterests.getInterestDataInfoIds().forEach((dataInfoId) -> {
            Collection<Subscriber> subscribers = sessionInterests.getInterests(dataInfoId);
            if (subscribers != null && !subscribers.isEmpty()) {
                checkDataInfoIds.add(dataInfoId);
            }
        });
        Map<String/*address*/, Collection<String>/*dataInfoIds*/> map = calculateDataNode(checkDataInfoIds);

        map.forEach((address, dataInfoIds) -> {

            //TODO asynchronous fetch version
            Map<String/*datacenter*/, Map<String/*datainfoid*/, Long>> dataVersions = dataNodeService
                    .fetchDataVersion(URL.valueOf(address), dataInfoIds);

            if (dataVersions != null) {
                sessionRegistryStrategy.doFetchChangDataProcess(dataVersions);
            } else {
                LOGGER.warn("Fetch no change data versions info from {}", address);
            }
        });

    }

    private Map<String, Collection<String>> calculateDataNode(Collection<String> dataInfoIds) {

        Map<String, Collection<String>> map = new HashMap<>();
        if (dataInfoIds != null) {
            dataInfoIds.forEach(dataInfoId -> {
                Node dataNode = dataNodeManager.getNode(dataInfoId);
                URL url = new URL(dataNode.getNodeUrl().getIpAddress(),
                        sessionServerConfig.getDataServerPort());
                Collection<String> list = map.computeIfAbsent(url.getAddressString(),
                        k -> new ArrayList<>());
                list.add(dataInfoId);
            });
        }

        return map;
    }

    private void checkConnect(StoreData storeData) {

        BaseInfo baseInfo = (BaseInfo) storeData;

        Server sessionServer = boltExchange.getServer(sessionServerConfig.getServerPort());

        Channel channel = sessionServer.getChannel(baseInfo.getSourceAddress());

        if (channel == null) {
            throw new RuntimeException(String.format(
                "Register address %s  has not connected session server!",
                baseInfo.getSourceAddress()));
        }

    }

    /**
     * Getter method for property <tt>sessionInterests</tt>.
     *
     * @return property value of sessionInterests
     */
    public Interests getSessionInterests() {
        return sessionInterests;
    }

    /**
     * Getter method for property <tt>sessionDataStore</tt>.
     *
     * @return property value of sessionDataStore
     */
    public DataStore getSessionDataStore() {
        return sessionDataStore;
    }

    /**
     * Getter method for property <tt>taskListenerManager</tt>.
     *
     * @return property value of taskListenerManager
     */
    public TaskListenerManager getTaskListenerManager() {
        return taskListenerManager;
    }
}