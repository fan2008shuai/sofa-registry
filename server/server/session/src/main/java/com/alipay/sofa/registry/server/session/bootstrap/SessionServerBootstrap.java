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
package com.alipay.sofa.registry.server.session.bootstrap;

import com.alipay.sofa.registry.common.model.Node;
import com.alipay.sofa.registry.common.model.constants.ValueConstants;
import com.alipay.sofa.registry.common.model.metaserver.FetchProvideDataRequest;
import com.alipay.sofa.registry.common.model.metaserver.NodeChangeResult;
import com.alipay.sofa.registry.common.model.metaserver.ProvideData;
import com.alipay.sofa.registry.common.model.metaserver.SessionNode;
import com.alipay.sofa.registry.common.model.store.URL;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.net.NetUtil;
import com.alipay.sofa.registry.remoting.ChannelHandler;
import com.alipay.sofa.registry.remoting.Client;
import com.alipay.sofa.registry.remoting.Server;
import com.alipay.sofa.registry.remoting.exchange.Exchange;
import com.alipay.sofa.registry.remoting.exchange.NodeExchanger;
import com.alipay.sofa.registry.server.session.node.NodeManager;
import com.alipay.sofa.registry.server.session.node.NodeManagerFactory;
import com.alipay.sofa.registry.server.session.node.RaftClientManager;
import com.alipay.sofa.registry.server.session.node.SessionProcessIdGenerator;
import com.alipay.sofa.registry.server.session.remoting.handler.AbstractClientHandler;
import com.alipay.sofa.registry.server.session.remoting.handler.AbstractServerHandler;
import com.alipay.sofa.registry.server.session.scheduler.ExecutorManager;
import com.alipay.sofa.registry.task.batcher.TaskDispatchers;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The type Session server bootstrap.
 * @author shangyu.wh
 * @version $Id : SessionServerBootstrap.java, v 0.1 2017-11-14 11:44 synex Exp $
 */
public class SessionServerBootstrap {

    private static final Logger               LOGGER         = LoggerFactory
                                                                 .getLogger(SessionServerBootstrap.class);

    @Autowired
    private SessionServerConfig               sessionServerConfig;

    @Autowired
    private Exchange                          boltExchange;

    @Autowired
    private Exchange                          jerseyExchange;

    @Autowired
    private ExecutorManager                   executorManager;

    @Resource(name = "serverHandlers")
    private Collection<AbstractServerHandler> serverHandlers;

    @Resource(name = "dataClientHandlers")
    private Collection<AbstractClientHandler> dataClientHandlers;

    @Autowired
    private NodeManager                       dataNodeManager;

    @Autowired
    private NodeManager                       metaNodeManager;

    @Autowired
    protected NodeExchanger                   metaNodeExchanger;

    @Autowired
    private ResourceConfig                    jerseyResourceConfig;

    @Autowired
    private ApplicationContext                applicationContext;

    @Autowired
    private RaftClientManager                 raftClientManager;

    private Server                            server;

    private Server                            httpServer;

    private Client                            dataClient;

    private Client                            metaClient;

    private AtomicBoolean                     metaStart      = new AtomicBoolean(false);

    private AtomicBoolean                     schedulerStart = new AtomicBoolean(false);

    private AtomicBoolean                     httpStart      = new AtomicBoolean(false);

    private AtomicBoolean                     serverStart    = new AtomicBoolean(false);

    private AtomicBoolean                     dataStart      = new AtomicBoolean(false);

    /**
     * Do initialized.
     */
    public void doInitialized() {
        try {
            initEnvironment();

            startRaftClient();

            connectMetaServer();

            startScheduler();

            openHttpServer();

            openSessionServer();

            connectDataServer();

            LOGGER.info("Initialized Session Server...");

            Runtime.getRuntime().addShutdownHook(new Thread(this::doStop));
        } catch (Throwable e) {
            LOGGER.error("Cannot bootstrap session server :", e);
            throw new RuntimeException("Cannot bootstrap session server :", e);
        }
    }

    /**
     * Destroy.
     */
    public void destroy() {
        doStop();
    }

    private void doStop() {
        try {
            LOGGER.info("{} Shutting down Session Server..", new Date().toString());

            executorManager.stopScheduler();
            TaskDispatchers.stopDefaultSingleTaskDispatcher();
            closeClients();
            stopHttpServer();
            stopServer();
        } catch (Throwable e) {
            LOGGER.error("Shutting down Session Server error!", e);
        }
        LOGGER.info("{} Session server is now shutdown...", new Date().toString());
    }

    private void initEnvironment() {
        LOGGER.info("Session server Environment: DataCenter {},Region {},ProcessId {}",
            sessionServerConfig.getSessionServerDataCenter(),
            sessionServerConfig.getSessionServerRegion(),
            SessionProcessIdGenerator.getSessionProcessId());
    }

    private void startScheduler() {

        try {
            if (schedulerStart.compareAndSet(false, true)) {
                executorManager.startScheduler();
                LOGGER.info("Session Scheduler started!");
            }
        } catch (Exception e) {
            schedulerStart.set(false);
            LOGGER.error("Session Scheduler start error!", e);
            throw new RuntimeException("Session Scheduler start error!", e);
        }
    }

    private void openSessionServer() {
        try {
            if (serverStart.compareAndSet(false, true)) {
                server = boltExchange.open(new URL(NetUtil.getLocalAddress().getHostAddress(),
                    sessionServerConfig.getServerPort()), serverHandlers
                    .toArray(new ChannelHandler[serverHandlers.size()]));

                LOGGER.info("Session server started! port:{}", sessionServerConfig.getServerPort());
            }
        } catch (Exception e) {
            serverStart.set(false);
            LOGGER.error("Session server start error! port:{}",
                sessionServerConfig.getServerPort(), e);
            throw new RuntimeException("Session server start error!", e);
        }
    }

    private void connectDataServer() {
        try {
            if (dataStart.compareAndSet(false, true)) {
                Collection<Node> dataNodes = dataNodeManager.getDataCenterNodes();
                if (CollectionUtils.isEmpty(dataNodes)) {
                    dataNodeManager.getAllDataCenterNodes();
                    dataNodes = dataNodeManager.getDataCenterNodes();
                }
                if (!CollectionUtils.isEmpty(dataNodes)) {
                    for (Node dataNode : dataNodes) {
                        if (dataNode.getNodeUrl() == null
                            || dataNode.getNodeUrl().getIpAddress() == null) {
                            LOGGER
                                .error("get data node address error!url{}", dataNode.getNodeUrl());
                            continue;
                        }
                        dataClient = boltExchange.connect(
                            Exchange.DATA_SERVER_TYPE,
                            new URL(dataNode.getNodeUrl().getIpAddress(), sessionServerConfig
                                .getDataServerPort()), dataClientHandlers
                                .toArray(new ChannelHandler[dataClientHandlers.size()]));
                    }
                    LOGGER.info("Data server connected {} server! port:{}", dataNodes.size(),
                        sessionServerConfig.getDataServerPort());
                }
            }
        } catch (Exception e) {
            dataStart.set(false);
            LOGGER.error("Data server connected server error! port:{}",
                sessionServerConfig.getDataServerPort(), e);
            throw new RuntimeException("Data server connected server error!", e);
        }
    }

    private void startRaftClient() {
        raftClientManager.startRaftClient();
        LOGGER.info("Raft Client started! Leader:{}", raftClientManager.getLeader());
    }

    private void connectMetaServer() {
        try {
            if (metaStart.compareAndSet(false, true)) {
                // 与meta server的leader建立连接
                // fan: 此处并非与meta server的leader建立连接而是与meta server中的所有node都建立了连接
                metaClient = metaNodeExchanger.connectServer();

                int size = metaClient.getChannels().size();

                URL leaderUrl = new URL(raftClientManager.getLeader().getIp(),
                    sessionServerConfig.getMetaServerPort());

                // 将该session server注册至meta server
                registerSessionNode(leaderUrl);

                // fan: 又来一次获取所有的meta server nodes，其实在metaNodeExchanger.connectServer();中获取过一次
                getAllDataCenter();

                fetchStopPushSwitch(leaderUrl);

                LOGGER.info("MetaServer connected {} server! Port:{}", size,
                    sessionServerConfig.getMetaServerPort());
            }
        } catch (Exception e) {
            metaStart.set(false);
            LOGGER.error("MetaServer connected server error! Port:{}",
                sessionServerConfig.getMetaServerPort(), e);
            throw new RuntimeException("MetaServer connected server error!", e);
        }
    }

    // fan: session node注册到meta nodes的leader节点，meta leader node给session node返回data nodes
    private void registerSessionNode(URL leaderUrl) {
        URL clientUrl = new URL(NetUtil.getLocalAddress().getHostAddress(), 0);
        SessionNode sessionNode = new SessionNode(clientUrl,
            sessionServerConfig.getSessionServerRegion());
        Object ret = sendMetaRequest(sessionNode, leaderUrl);
        if (ret instanceof NodeChangeResult) {
            NodeChangeResult nodeChangeResult = (NodeChangeResult) ret;
            NodeManager nodeManager = NodeManagerFactory.getNodeManager(nodeChangeResult
                .getNodeType());
            //update data node info
            nodeManager.updateNodes(nodeChangeResult);
            LOGGER.info("Register MetaServer Session Node success!get data node list {}",
                nodeChangeResult.getNodes());
        }
    }

    private void fetchStopPushSwitch(URL leaderUrl) {
        FetchProvideDataRequest fetchProvideDataRequest = new FetchProvideDataRequest(
            ValueConstants.STOP_PUSH_DATA_SWITCH_DATA_ID);
        Object ret = sendMetaRequest(fetchProvideDataRequest, leaderUrl);
        if (ret instanceof ProvideData) {
            ProvideData provideData = (ProvideData) ret;
            if (provideData.getProvideData() == null
                || provideData.getProvideData().getObject() == null) {
                LOGGER.info("Fetch session stop push switch no data existed,config not change!");
                return;
            }
            String data = (String) provideData.getProvideData().getObject();
            sessionServerConfig.setStopPushSwitch(Boolean.valueOf(data));
            if (data != null) {
                if (!Boolean.valueOf(data)) {
                    //stop push init on,then begin fetch data schedule task
                    // fan: 如果data server不主动推送数据，则session server主动拉取数据
                    sessionServerConfig.setBeginDataFetchTask(true);
                }
            }
            LOGGER.info("Fetch session stop push data switch {} success!", data);
        } else {
            LOGGER.info("Fetch session stop push switch data null,config not change!");
        }
    }

    private Object sendMetaRequest(Object request, URL leaderUrl) {
        Object ret;
        try {
            ret = metaClient.sendSync(metaClient.getChannel(leaderUrl), request,
                sessionServerConfig.getMetaNodeExchangeTimeOut());
        } catch (Exception e) {
            URL leaderUrlNew = new URL(raftClientManager.refreshLeader().getIp(),
                sessionServerConfig.getMetaServerPort());
            LOGGER.warn("request send error!It will be retry once to new leader {}!", leaderUrlNew);
            ret = metaClient.sendSync(metaClient.getChannel(leaderUrlNew), request,
                sessionServerConfig.getMetaNodeExchangeTimeOut());
        }
        return ret;
    }

    private void getAllDataCenter() {
        //get meta node info
        metaNodeManager.getAllDataCenterNodes();
        LOGGER.info("Get all dataCenter from meta Server success!");
    }

    private void openHttpServer() {
        try {
            if (httpStart.compareAndSet(false, true)) {
                bindResourceConfig();
                httpServer = jerseyExchange.open(
                    new URL(NetUtil.getLocalAddress().getHostAddress(), sessionServerConfig
                        .getHttpServerPort()), new ResourceConfig[] { jerseyResourceConfig });
                LOGGER.info("Open http server port {} success!",
                    sessionServerConfig.getHttpServerPort());
            }
        } catch (Exception e) {
            LOGGER.error("Open http server port {} error!",
                sessionServerConfig.getHttpServerPort(), e);
            httpStart.set(false);
            throw new RuntimeException("Open http server error!", e);
        }
    }

    private void bindResourceConfig() {
        registerInstances(Path.class);
        registerInstances(Provider.class);
    }

    private void registerInstances(Class<? extends Annotation> annotationType) {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(annotationType);
        if (beans != null && !beans.isEmpty()) {
            beans.forEach((beanName, bean) -> {
                jerseyResourceConfig.registerInstances(bean);
                jerseyResourceConfig.register(bean.getClass());
            });
        }
    }

    private void stopServer() {
        if (server != null && server.isOpen()) {
            server.close();
        }
    }

    private void closeClients() {
        if (dataClient != null && !dataClient.isClosed()) {
            dataClient.close();
        }
    }

    private void stopHttpServer() {
        if (httpServer != null && httpServer.isOpen()) {
            httpServer.close();
        }
    }

    /**
     * Getter method for property <tt>metaStart</tt>.
     *
     * @return property value of metaStart
     */
    public AtomicBoolean getMetaStart() {
        return metaStart;
    }

    /**
     * Getter method for property <tt>schedulerStart</tt>.
     *
     * @return property value of schedulerStart
     */
    public AtomicBoolean getSchedulerStart() {
        return schedulerStart;
    }

    /**
     * Getter method for property <tt>httpStart</tt>.
     *
     * @return property value of httpStart
     */
    public AtomicBoolean getHttpStart() {
        return httpStart;
    }

    /**
     * Getter method for property <tt>serverStart</tt>.
     *
     * @return property value of serverStart
     */
    public AtomicBoolean getServerStart() {
        return serverStart;
    }

    /**
     * Getter method for property <tt>dataStart</tt>.
     *
     * @return property value of dataStart
     */
    public AtomicBoolean getDataStart() {
        return dataStart;
    }
}