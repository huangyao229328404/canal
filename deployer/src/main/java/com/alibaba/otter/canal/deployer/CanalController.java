package com.alibaba.otter.canal.deployer;

import java.util.Map;
import java.util.Properties;

import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.alibaba.otter.canal.common.utils.AddressUtils;
import com.alibaba.otter.canal.common.zookeeper.ZkClientx;
import com.alibaba.otter.canal.common.zookeeper.ZookeeperPathUtils;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningData;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningListener;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitor;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitors;
import com.alibaba.otter.canal.deployer.InstanceConfig.InstanceMode;
import com.alibaba.otter.canal.deployer.monitor.InstanceAction;
import com.alibaba.otter.canal.deployer.monitor.InstanceConfigMonitor;
import com.alibaba.otter.canal.deployer.monitor.ManagerInstanceConfigMonitor;
import com.alibaba.otter.canal.deployer.monitor.SpringInstanceConfigMonitor;
import com.alibaba.otter.canal.instance.core.CanalInstance;
import com.alibaba.otter.canal.instance.core.CanalInstanceGenerator;
import com.alibaba.otter.canal.instance.manager.PlainCanalInstanceGenerator;
import com.alibaba.otter.canal.instance.manager.plain.PlainCanalConfigClient;
import com.alibaba.otter.canal.instance.spring.SpringCanalInstanceGenerator;
import com.alibaba.otter.canal.server.CanalMQStarter;
import com.alibaba.otter.canal.server.embedded.CanalServerWithEmbedded;
import com.alibaba.otter.canal.server.exception.CanalServerException;
import com.alibaba.otter.canal.server.netty.CanalServerWithNetty;
import com.google.common.base.Function;
import com.google.common.collect.MapMaker;
import com.google.common.collect.MigrateMap;

/**
 * canal调度控制器
 *
 * @author jianghang 2012-11-8 下午12:03:11
 * @version 1.0.0
 */
public class CanalController {

    private static final Logger                      logger   = LoggerFactory.getLogger(CanalController.class);
    private String                                   ip;
    private String                                   registerIp;
    private int                                      port;
    private int                                      adminPort;
    // 默认使用spring的方式载入
    private Map<String, InstanceConfig>              instanceConfigs;
    private InstanceConfig                           globalInstanceConfig;
    private Map<String, PlainCanalConfigClient>      managerClients;
    // 监听instance config的变化
    private boolean                                  autoScan = true;
    private InstanceAction                           defaultAction;
    private Map<InstanceMode, InstanceConfigMonitor> instanceConfigMonitors;
    private CanalServerWithEmbedded                  embededCanalServer;
    private CanalServerWithNetty                     canalServer;

    private CanalInstanceGenerator                   instanceGenerator;
    private ZkClientx                                zkclientx;

    private CanalMQStarter                           canalMQStarter;
    private String                                   adminUser;
    private String                                   adminPasswd;

    public CanalController(){
        this(System.getProperties());
    }

    public CanalController(final Properties properties){
        managerClients = MigrateMap.makeComputingMap(new Function<String, PlainCanalConfigClient>() {

            public PlainCanalConfigClient apply(String managerAddress) {
                return getManagerClient(managerAddress);
            }
        });

        // 初始化全局参数设置
        globalInstanceConfig = initGlobalConfig(properties);
        instanceConfigs = new MapMaker().makeMap();
        // 初始化instance config
        initInstanceConfig(properties);

        // init socketChannel
        String socketChannel = getProperty(properties, CanalConstants.CANAL_SOCKETCHANNEL);
        if (StringUtils.isNotEmpty(socketChannel)) {
            System.setProperty(CanalConstants.CANAL_SOCKETCHANNEL, socketChannel);
        }

        // 兼容1.1.0版本的ak/sk参数名
        String accesskey = getProperty(properties, "canal.instance.rds.accesskey");
        String secretkey = getProperty(properties, "canal.instance.rds.secretkey");
        if (StringUtils.isNotEmpty(accesskey)) {
            System.setProperty(CanalConstants.CANAL_ALIYUN_ACCESSKEY, accesskey);
        }
        if (StringUtils.isNotEmpty(secretkey)) {
            System.setProperty(CanalConstants.CANAL_ALIYUN_SECRETKEY, secretkey);
        }

        // 准备canal server
        ip = getProperty(properties, CanalConstants.CANAL_IP);
        registerIp = getProperty(properties, CanalConstants.CANAL_REGISTER_IP);
        port = Integer.valueOf(getProperty(properties, CanalConstants.CANAL_PORT, "11111"));
        adminPort = Integer.valueOf(getProperty(properties, CanalConstants.CANAL_ADMIN_PORT, "11110"));
        embededCanalServer = CanalServerWithEmbedded.instance();
        embededCanalServer.setCanalInstanceGenerator(instanceGenerator);// 设置自定义的instanceGenerator
        int metricsPort = Integer.valueOf(getProperty(properties, CanalConstants.CANAL_METRICS_PULL_PORT, "11112"));
        embededCanalServer.setMetricsPort(metricsPort);

        this.adminUser = getProperty(properties, CanalConstants.CANAL_ADMIN_USER);
        this.adminPasswd = getProperty(properties, CanalConstants.CANAL_ADMIN_PASSWD);
        embededCanalServer.setUser(getProperty(properties, CanalConstants.CANAL_USER));
        embededCanalServer.setPasswd(getProperty(properties, CanalConstants.CANAL_PASSWD));

        String canalWithoutNetty = getProperty(properties, CanalConstants.CANAL_WITHOUT_NETTY);
        if (canalWithoutNetty == null || "false".equals(canalWithoutNetty)) {
            canalServer = CanalServerWithNetty.instance();
            canalServer.setIp(ip);
            canalServer.setPort(port);
        }

        // 处理下ip为空，默认使用hostIp暴露到zk中
        if (StringUtils.isEmpty(ip) && StringUtils.isEmpty(registerIp)) {
            ip = registerIp = AddressUtils.getHostIp();
        }

        if (StringUtils.isEmpty(ip)) {
            ip = AddressUtils.getHostIp();
        }

        if (StringUtils.isEmpty(registerIp)) {
            registerIp = ip; // 兼容以前配置
        }
        //不使用zk，使用单机zk，使用集群zk
        final String zkServers = getProperty(properties, CanalConstants.CANAL_ZKSERVERS);
        if (StringUtils.isNotEmpty(zkServers)) {
            zkclientx = ZkClientx.getZkClient(zkServers);
            // 初始化系统目录
            zkclientx.createPersistent(ZookeeperPathUtils.DESTINATION_ROOT_NODE, true);
            zkclientx.createPersistent(ZookeeperPathUtils.CANAL_CLUSTER_ROOT_NODE, true);
        }
        //CanalInstance运行状态监控；在zk中登记当前running的实例IP和端口，方便客户端获取
        final ServerRunningData serverData = new ServerRunningData(registerIp + ":" + port);
        ServerRunningMonitors.setServerData(serverData);
        ServerRunningMonitors.setRunningMonitors(MigrateMap.makeComputingMap(new Function<String, ServerRunningMonitor>() {
            //此处为MigrateMap.makeComputingMap的特性，当get(destination)没有值时会触发该实现
            public ServerRunningMonitor apply(final String destination) {
                ServerRunningMonitor runningMonitor = new ServerRunningMonitor(serverData);
                runningMonitor.setDestination(destination);
                runningMonitor.setListener(new ServerRunningListener() {
                    //ServerRunningMonitor执行processActiveEnter时触发
                    public void processActiveEnter() {
                        try {
                            MDC.put(CanalConstants.MDC_DESTINATION, String.valueOf(destination));
                            embededCanalServer.start(destination);
                            //开启了MQ中间件的投递则，启动对应实例的监听
                            if (canalMQStarter != null) {
                                canalMQStarter.startDestination(destination);
                            }
                        } finally {
                            MDC.remove(CanalConstants.MDC_DESTINATION);
                        }
                    }
                    //ServerRunningMonitor执行processActiveExit时触发
                    public void processActiveExit() {
                        try {
                            MDC.put(CanalConstants.MDC_DESTINATION, String.valueOf(destination));
                            if (canalMQStarter != null) {
                                canalMQStarter.stopDestination(destination);
                            }
                            embededCanalServer.stop(destination);
                        } finally {
                            MDC.remove(CanalConstants.MDC_DESTINATION);
                        }
                    }
                    //ServerRunningMonitor执行processStart时触发
                    public void processStart() {
                        try {
                            if (zkclientx != null) {
                                final String path = ZookeeperPathUtils.getDestinationClusterNode(destination,
                                    registerIp + ":" + port);
                                initCid(path);
                                zkclientx.subscribeStateChanges(new IZkStateListener() {

                                    public void handleStateChanged(KeeperState state) throws Exception {

                                    }

                                    public void handleNewSession() throws Exception {
                                        initCid(path);
                                    }

                                    @Override
                                    public void handleSessionEstablishmentError(Throwable error) throws Exception {
                                        logger.error("failed to connect to zookeeper", error);
                                    }
                                });
                            }
                        } finally {
                            MDC.remove(CanalConstants.MDC_DESTINATION);
                        }
                    }

                    public void processStop() {
                        try {
                            MDC.put(CanalConstants.MDC_DESTINATION, String.valueOf(destination));
                            if (zkclientx != null) {
                                final String path = ZookeeperPathUtils.getDestinationClusterNode(destination,
                                    registerIp + ":" + port);
                                releaseCid(path);
                            }
                        } finally {
                            MDC.remove(CanalConstants.MDC_DESTINATION);
                        }
                    }

                });
                if (zkclientx != null) {
                    runningMonitor.setZkClient(zkclientx);
                }
                // 触发创建一下cid节点,开启实例启动的抢占和运行的监听ServerRunningMonitor
                runningMonitor.init();
                return runningMonitor;
            }
        }));

        // 初始化monitor机制，autoScan机制相关代码，此处也是设置默认，真正的实在start()
        autoScan = BooleanUtils.toBoolean(getProperty(properties, CanalConstants.CANAL_AUTO_SCAN));
        if (autoScan) {
            defaultAction = new InstanceAction() {

                public void start(String destination) {
                    InstanceConfig config = instanceConfigs.get(destination);
                    if (config == null) {
                        // 重新读取一下instance config
                        config = parseInstanceConfig(properties, destination);
                        instanceConfigs.put(destination, config);
                    }

                    if (!embededCanalServer.isStart(destination)) {
                        // HA机制启动,真正针对实例的配置启动moniter机制，即running节点抢占
                        ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(destination);
                        //如果是lazy
                        if (!config.getLazy() && !runningMonitor.isStart()) {
                            runningMonitor.start();
                        }
                    }

                    logger.info("auto notify start {} successful.", destination);
                }

                public void stop(String destination) {
                    // 此处的stop，代表强制退出，非HA机制，所以需要退出HA的monitor和配置信息
                    InstanceConfig config = instanceConfigs.remove(destination);
                    if (config != null) {
                        embededCanalServer.stop(destination);
                        ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(destination);
                        if (runningMonitor.isStart()) {
                            runningMonitor.stop();
                        }
                    }

                    logger.info("auto notify stop {} successful.", destination);
                }

                public void reload(String destination) {
                    // 目前任何配置变化，直接重启，简单处理
                    stop(destination);
                    start(destination);

                    logger.info("auto notify reload {} successful.", destination);
                }

                @Override
                public void release(String destination) {
                    // 此处的release，代表强制释放，主要针对HA机制释放运行，让给其他机器抢占
                    InstanceConfig config = instanceConfigs.get(destination);
                    if (config != null) {
                        ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(destination);
                        if (runningMonitor.isStart()) {
                            boolean release = runningMonitor.release();
                            if (!release) {
                                // 如果是单机模式,则直接清除配置
                                instanceConfigs.remove(destination);
                                // 停掉服务
                                runningMonitor.stop();
                                if (instanceConfigMonitors.containsKey(InstanceConfig.InstanceMode.MANAGER)) {
                                    ManagerInstanceConfigMonitor monitor = (ManagerInstanceConfigMonitor) instanceConfigMonitors.get(InstanceConfig.InstanceMode.MANAGER);
                                    Map<String, InstanceAction> instanceActions = monitor.getActions();
                                    if (instanceActions.containsKey(destination)) {
                                        // 清除内存中的autoScan cache
                                        monitor.release(destination);
                                    }
                                }
                            }
                        }
                    }

                    logger.info("auto notify release {} successful.", destination);
                }
            };

            instanceConfigMonitors = MigrateMap.makeComputingMap(new Function<InstanceMode, InstanceConfigMonitor>() {

                public InstanceConfigMonitor apply(InstanceMode mode) {
                    int scanInterval = Integer.valueOf(getProperty(properties,
                        CanalConstants.CANAL_AUTO_SCAN_INTERVAL,
                        "5"));

                    if (mode.isSpring()) {
                        SpringInstanceConfigMonitor monitor = new SpringInstanceConfigMonitor();
                        monitor.setScanIntervalInSecond(scanInterval);
                        monitor.setDefaultAction(defaultAction);
                        // 设置conf目录，默认是user.dir + conf目录组成
                        String rootDir = getProperty(properties, CanalConstants.CANAL_CONF_DIR);
                        if (StringUtils.isEmpty(rootDir)) {
                            rootDir = "../conf";
                        }

                        if (StringUtils.equals("otter-canal", System.getProperty("appName"))) {
                            monitor.setRootConf(rootDir);
                        } else {
                            // eclipse debug模式
                            monitor.setRootConf("src/main/resources/");
                        }
                        return monitor;
                    } else if (mode.isManager()) {
                        ManagerInstanceConfigMonitor monitor = new ManagerInstanceConfigMonitor();
                        monitor.setScanIntervalInSecond(scanInterval);
                        monitor.setDefaultAction(defaultAction);
                        String managerAddress = getProperty(properties, CanalConstants.CANAL_ADMIN_MANAGER);
                        monitor.setConfigClient(getManagerClient(managerAddress));
                        return monitor;
                    } else {
                        throw new UnsupportedOperationException("unknow mode :" + mode + " for monitor");
                    }
                }
            });
        }
    }

    private InstanceConfig initGlobalConfig(Properties properties) {
        String adminManagerAddress = getProperty(properties, CanalConstants.CANAL_ADMIN_MANAGER);
        InstanceConfig globalConfig = new InstanceConfig();
        String modeStr = getProperty(properties, CanalConstants.getInstanceModeKey(CanalConstants.GLOBAL_NAME));
        if (StringUtils.isNotEmpty(adminManagerAddress)) {
            // 如果指定了manager地址,则强制适用manager
            globalConfig.setMode(InstanceMode.MANAGER);
        } else if (StringUtils.isNotEmpty(modeStr)) {
            globalConfig.setMode(InstanceMode.valueOf(StringUtils.upperCase(modeStr)));
        }

        String lazyStr = getProperty(properties, CanalConstants.getInstancLazyKey(CanalConstants.GLOBAL_NAME));
        if (StringUtils.isNotEmpty(lazyStr)) {
            globalConfig.setLazy(Boolean.valueOf(lazyStr));
        }

        String managerAddress = getProperty(properties,
            CanalConstants.getInstanceManagerAddressKey(CanalConstants.GLOBAL_NAME));
        if (StringUtils.isNotEmpty(managerAddress)) {
            //全局的canal.instance.global.manager.address可以配置为${canal.admin.manager}，用于取canal.admin.manager的配置值
            if (StringUtils.equals(managerAddress, "${canal.admin.manager}")) {
                managerAddress = adminManagerAddress;
            }

            globalConfig.setManagerAddress(managerAddress);
        }

        String springXml = getProperty(properties, CanalConstants.getInstancSpringXmlKey(CanalConstants.GLOBAL_NAME));
        if (StringUtils.isNotEmpty(springXml)) {
            globalConfig.setSpringXml(springXml);
        }
        //canal实例的生成器，根据实例Mode=spring或者manager来构建
        instanceGenerator = new CanalInstanceGenerator() {

            public CanalInstance generate(String destination) {
                InstanceConfig config = instanceConfigs.get(destination);
                if (config == null) {
                    throw new CanalServerException("can't find destination:" + destination);
                }

                if (config.getMode().isManager()) {
                    //spring.xml中的配置从远程admin中获取
                    PlainCanalInstanceGenerator instanceGenerator = new PlainCanalInstanceGenerator(properties);
                    instanceGenerator.setCanalConfigClient(managerClients.get(config.getManagerAddress()));
                    instanceGenerator.setSpringXml(config.getSpringXml());
                    return instanceGenerator.generate(destination);
                } else if (config.getMode().isSpring()) {
                    //从本地的canal.properties和instance.properties中获取spring.xml中的配置
                    SpringCanalInstanceGenerator instanceGenerator = new SpringCanalInstanceGenerator();
                    instanceGenerator.setSpringXml(config.getSpringXml());
                    return instanceGenerator.generate(destination);
                } else {
                    throw new UnsupportedOperationException("unknow mode :" + config.getMode());
                }

            }

        };

        return globalConfig;
    }

    private PlainCanalConfigClient getManagerClient(String managerAddress) {
        return new PlainCanalConfigClient(managerAddress, this.adminUser, this.adminPasswd, this.registerIp, adminPort);
    }

    private void initInstanceConfig(Properties properties) {
        //获取canal.destinations=配置的实例，默认为example
        String destinationStr = getProperty(properties, CanalConstants.CANAL_DESTINATIONS);
        String[] destinations = StringUtils.split(destinationStr, CanalConstants.CANAL_DESTINATION_SPLIT);
        //对每个实例进行对应的配置
        for (String destination : destinations) {
            //首先加载全局的配置，即canal.properties，然后加载对应实例目录下的instance.properties如classpath:example/instance.properties覆盖全局
            InstanceConfig config = parseInstanceConfig(properties, destination);
            InstanceConfig oldConfig = instanceConfigs.put(destination, config);

            if (oldConfig != null) {
                logger.warn("destination:{} old config:{} has replace by new config:{}", destination, oldConfig, config);
            }
        }
    }

    private InstanceConfig parseInstanceConfig(Properties properties, String destination) {
        String adminManagerAddress = getProperty(properties, CanalConstants.CANAL_ADMIN_MANAGER);
        InstanceConfig config = new InstanceConfig(globalInstanceConfig);
        String modeStr = getProperty(properties, CanalConstants.getInstanceModeKey(destination));
        if (StringUtils.isNotEmpty(adminManagerAddress)) {
            // 如果指定了manager地址,则强制适用manager
            config.setMode(InstanceMode.MANAGER);
        } else if (StringUtils.isNotEmpty(modeStr)) {
            config.setMode(InstanceMode.valueOf(StringUtils.upperCase(modeStr)));
        }

        String lazyStr = getProperty(properties, CanalConstants.getInstancLazyKey(destination));
        if (!StringUtils.isEmpty(lazyStr)) {
            config.setLazy(Boolean.valueOf(lazyStr));
        }

        if (config.getMode().isManager()) {
            String managerAddress = getProperty(properties, CanalConstants.getInstanceManagerAddressKey(destination));
            if (StringUtils.isNotEmpty(managerAddress)) {
                if (StringUtils.equals(managerAddress, "${canal.admin.manager}")) {
                    managerAddress = adminManagerAddress;
                }
                config.setManagerAddress(managerAddress);
            }
        } else if (config.getMode().isSpring()) {
            String springXml = getProperty(properties, CanalConstants.getInstancSpringXmlKey(destination));
            if (StringUtils.isNotEmpty(springXml)) {
                config.setSpringXml(springXml);
            }
        }

        return config;
    }

    public static String getProperty(Properties properties, String key, String defaultValue) {
        String value = getProperty(properties, key);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        } else {
            return value;
        }
    }

    public static String getProperty(Properties properties, String key) {
        key = StringUtils.trim(key);
        String value = System.getProperty(key);

        if (value == null) {
            //大多数操作系统使用环境变量，可以把配置信息传递给应用程序
            value = System.getenv(key);
        }

        if (value == null) {
            value = properties.getProperty(key);
        }

        return StringUtils.trim(value);
    }

    public void start() throws Throwable {
        logger.info("## start the canal server[{}({}):{}]", ip, registerIp, port);
        // 创建整个canal的工作节点
        final String path = ZookeeperPathUtils.getCanalClusterNode(registerIp + ":" + port);
        //非HA模式，不做任何处理，HA模式下即:抢占zk的临时节点，临时节点不存在则创建
        initCid(path);
        if (zkclientx != null) {
            //HA模式下，监听会话，会话结束则重新进行临时节点的抢占
            this.zkclientx.subscribeStateChanges(new IZkStateListener() {

                public void handleStateChanged(KeeperState state) throws Exception {

                }

                public void handleNewSession() throws Exception {
                    //重新抢占
                    initCid(path);
                }

                @Override
                public void handleSessionEstablishmentError(Throwable error) throws Exception {
                    logger.error("failed to connect to zookeeper", error);
                }
            });
        }
        // 优先启动embeded服务
        embededCanalServer.start();
        // 尝试启动一下非lazy状态的通道
        for (Map.Entry<String, InstanceConfig> entry : instanceConfigs.entrySet()) {
            final String destination = entry.getKey();
            InstanceConfig config = entry.getValue();
            // 创建destination的工作节点
            if (!embededCanalServer.isStart(destination)) {
                // HA机制启动
                ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(destination);
                if (!config.getLazy() && !runningMonitor.isStart()) {
                    runningMonitor.start();
                }
            }

            if (autoScan) {
                instanceConfigMonitors.get(config.getMode()).register(destination, defaultAction);
            }
        }

        if (autoScan) {
            instanceConfigMonitors.get(globalInstanceConfig.getMode()).start();
            for (InstanceConfigMonitor monitor : instanceConfigMonitors.values()) {
                if (!monitor.isStart()) {
                    monitor.start();
                }
            }
        }

        // 启动网络接口，即客户端连接的网络接口，端口默认11111
        if (canalServer != null) {
            canalServer.start();
        }
    }

    public void stop() throws Throwable {

        if (canalServer != null) {
            canalServer.stop();
        }

        if (autoScan) {
            for (InstanceConfigMonitor monitor : instanceConfigMonitors.values()) {
                if (monitor.isStart()) {
                    monitor.stop();
                }
            }
        }

        for (ServerRunningMonitor runningMonitor : ServerRunningMonitors.getRunningMonitors().values()) {
            if (runningMonitor.isStart()) {
                runningMonitor.stop();
            }
        }

        // 释放canal的工作节点
        releaseCid(ZookeeperPathUtils.getCanalClusterNode(registerIp + ":" + port));
        logger.info("## stop the canal server[{}({}):{}]", ip, registerIp, port);

        if (zkclientx != null) {
            zkclientx.close();
        }

        // 关闭时清理缓存
        if (instanceConfigs != null) {
            instanceConfigs.clear();
        }
        if (managerClients != null) {
            managerClients.clear();
        }
        if (instanceConfigMonitors != null) {
            instanceConfigMonitors.clear();
        }

        ZkClientx.clearClients();
    }

    private void initCid(String path) {
        // logger.info("## init the canalId = {}", cid);
        // 初始化系统目录
        if (zkclientx != null) {
            try {
                zkclientx.createEphemeral(path);
            } catch (ZkNoNodeException e) {
                // 如果父目录不存在，则创建
                String parentDir = path.substring(0, path.lastIndexOf('/'));
                zkclientx.createPersistent(parentDir, true);
                zkclientx.createEphemeral(path);
            } catch (ZkNodeExistsException e) {
                // ignore
                // 因为第一次启动时创建了cid,但在stop/start的时可能会关闭和新建,允许出现NodeExists问题s
            }

        }
    }

    private void releaseCid(String path) {
        // logger.info("## release the canalId = {}", cid);
        // 初始化系统目录
        if (zkclientx != null) {
            zkclientx.delete(path);
        }
    }

    public CanalMQStarter getCanalMQStarter() {
        return canalMQStarter;
    }

    public void setCanalMQStarter(CanalMQStarter canalMQStarter) {
        this.canalMQStarter = canalMQStarter;
    }

    public Map<InstanceMode, InstanceConfigMonitor> getInstanceConfigMonitors() {
        return instanceConfigMonitors;
    }

    public Map<String, InstanceConfig> getInstanceConfigs() {
        return instanceConfigs;
    }

}
