package com.alipay.sofa.registry.client.demo;

import com.alipay.sofa.registry.client.api.RegistryClientConfig;
import com.alipay.sofa.registry.client.api.registration.PublisherRegistration;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClient;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClientConfigBuilder;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Created by fan.shuai on 2019/7/12.
 */
public class PublishDemo {

    @Test
    public void publish() {
        // 构建客户端实例
        RegistryClientConfig config = DefaultRegistryClientConfigBuilder.start().setRegistryEndpoint("127.0.0.1").setRegistryEndpointPort(9603).build();
        DefaultRegistryClient registryClient = new DefaultRegistryClient(config);
        registryClient.init();

        // 构造发布者注册表
        String dataId = "com.alipay.test.demo.service:1.0@DEFAULT";
        PublisherRegistration registration = new PublisherRegistration(dataId);

        // 将注册表注册进客户端并发布数据
        registryClient.register(registration, "10.10.1.1:12200?xx=yy");

        try {
            TimeUnit.SECONDS.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
