package com.alibaba.otter.canal.adapter.launcher.config;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.stereotype.Component;

/**
 * curator 配置类
 * Curator是Netflix公司开源的一套Zookeeper客户端框架。了解过Zookeeper原生API都会清楚其复杂度。
 * Curator帮助我们在其基础上进行封装、实现一些开发细节，包括接连重连、反复注册Watcher和NodeExistsException等。
 * 目前已经作为Apache的顶级项目出现，是最流行的Zookeeper客户端之一。从编码风格上来讲，它提供了基于Fluent的编程风格支持。
 *
 * 除此之外，Curator还提供了Zookeeper的各种应用场景：Recipe、共享锁服务、Master选举机制和分布式计数器等
 *
 * @author rewerma @ 2018-10-20
 * @version 1.0.0
 */
@Component
public class CuratorClient {

    @Resource
    private AdapterCanalConfig adapterCanalConfig;

    private CuratorFramework   curator = null;

    @PostConstruct
    public void init() {
        if (adapterCanalConfig.getZookeeperHosts() != null) {
            curator = CuratorFrameworkFactory.builder()
                .connectString(adapterCanalConfig.getZookeeperHosts())
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .sessionTimeoutMs(6000)
                .connectionTimeoutMs(3000)
                .namespace("canal-adapter")
                .build();
            curator.start();
        }
    }

    public CuratorFramework getCurator() {
        return curator;
    }
}
