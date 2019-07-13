package com.alipay.sofa.registry.client.demo;

import com.alipay.sofa.registry.client.api.RegistryClientConfig;
import com.alipay.sofa.registry.client.api.SubscriberDataObserver;
import com.alipay.sofa.registry.client.api.model.UserData;
import com.alipay.sofa.registry.client.api.registration.SubscriberRegistration;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClient;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClientConfigBuilder;
import com.alipay.sofa.registry.core.model.ScopeEnum;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Created by fan.shuai on 2019/7/12.
 */
public class SubscribeDemo {

    @Test
    public void subscribe() {
        // 构建客户端实例
        RegistryClientConfig config = DefaultRegistryClientConfigBuilder.start().setRegistryEndpoint("127.0.0.1").setRegistryEndpointPort(9603).build();
        DefaultRegistryClient registryClient = new DefaultRegistryClient(config);
        registryClient.init();

        // 创建 SubscriberDataObserver
        SubscriberDataObserver subscriberDataObserver = new SubscriberDataObserver() {
            public void handleData(String dataId, UserData userData) {
                System.out.println("receive data success, dataId: " + dataId + ", data: " + userData);
            }
        };

        // 构造订阅者注册表，设置订阅维度，ScopeEnum 共有三种级别 zone, dataCenter, global
        String dataId = "com.alipay.test.demo.service:1.0@DEFAULT";
        SubscriberRegistration registration = new SubscriberRegistration(dataId, subscriberDataObserver);
        registration.setScopeEnum(ScopeEnum.global);

        // 将注册表注册进客户端并订阅数据，订阅到的数据会以回调的方式通知 SubscriberDataObserver
        registryClient.register(registration);

        try {
            TimeUnit.SECONDS.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
