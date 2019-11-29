package com.alibaba.otter.canal.client.adapter.rdb;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.otter.canal.client.adapter.OuterAdapter;
import com.alibaba.otter.canal.client.adapter.rdb.config.ConfigLoader;
import com.alibaba.otter.canal.client.adapter.rdb.config.MappingConfig;
import com.alibaba.otter.canal.client.adapter.rdb.config.MirrorDbConfig;
import com.alibaba.otter.canal.client.adapter.rdb.monitor.RdbConfigMonitor;
import com.alibaba.otter.canal.client.adapter.rdb.service.RdbEtlService;
import com.alibaba.otter.canal.client.adapter.rdb.service.RdbMirrorDbSyncService;
import com.alibaba.otter.canal.client.adapter.rdb.service.RdbSyncService;
import com.alibaba.otter.canal.client.adapter.rdb.support.SyncUtil;
import com.alibaba.otter.canal.client.adapter.support.Dml;
import com.alibaba.otter.canal.client.adapter.support.EtlResult;
import com.alibaba.otter.canal.client.adapter.support.OuterAdapterConfig;
import com.alibaba.otter.canal.client.adapter.support.SPI;
import com.alibaba.otter.canal.client.adapter.support.Util;

/**
 * RDB适配器实现类
 *
 * @author rewerma 2018-11-7 下午06:45:49
 * @version 1.0.0
 */
@SPI("rdb")
public class RdbAdapter implements OuterAdapter {

    private static Logger                           logger              = LoggerFactory.getLogger(RdbAdapter.class);
    /*
    * key=文件名，value=MappingConfig
    * 如:xxx.yml=MappingConfig映射实例
    * */
    private Map<String, MappingConfig>              rdbMapping          = new ConcurrentHashMap<>();                // 文件名对应配置
    /*
     * 非镜像库(默认为非镜像库)
     *        tcp模式:
     *             key=canal实例名_database-table
     *             value=文件名对应配置关系map
     *
     *        kafka,rabbitMQ,rocketMQ模式:
     *             key=canal实例名-groupId_database-table
     *             value=文件名对应配置关系map
     */
    private Map<String, Map<String, MappingConfig>> mappingConfigCache  = new ConcurrentHashMap<>();                // 库名-表名对应配置
    /*
     * 镜像库(mirrorDb配置为true)：
     *        key=canal实例名_database
     *        value=MirrorDbConfig
     */
    private Map<String, MirrorDbConfig>             mirrorDbConfigCache = new ConcurrentHashMap<>();                // 镜像库配置

    private DruidDataSource                         dataSource;

    private RdbSyncService                          rdbSyncService;
    private RdbMirrorDbSyncService                  rdbMirrorDbSyncService;

    private RdbConfigMonitor                        rdbConfigMonitor;

    private Properties                              envProperties;

    public Map<String, MappingConfig> getRdbMapping() {
        return rdbMapping;
    }

    public Map<String, Map<String, MappingConfig>> getMappingConfigCache() {
        return mappingConfigCache;
    }

    public Map<String, MirrorDbConfig> getMirrorDbConfigCache() {
        return mirrorDbConfigCache;
    }

    /**
     * 初始化方法
     *
     * @param configuration 外部适配器配置信息
     */
    @Override
    public void init(OuterAdapterConfig configuration, Properties envProperties) {
        this.envProperties = envProperties;
        //1.加载文件名对应配置
        Map<String, MappingConfig> rdbMappingTmp = ConfigLoader.load(envProperties);
        // 过滤不匹配的key的配置
        rdbMappingTmp.forEach((key, mappingConfig) -> {
            if ((mappingConfig.getOuterAdapterKey() == null && configuration.getKey() == null)
                || (mappingConfig.getOuterAdapterKey() != null && mappingConfig.getOuterAdapterKey()
                    .equalsIgnoreCase(configuration.getKey()))) {
                rdbMapping.put(key, mappingConfig);
            }
        });

        if (rdbMapping.isEmpty()) {
            throw new RuntimeException("No rdb adapter found for config key: " + configuration.getKey());
        }
        //2. 加载库名-表名对应配置
        //3.加载镜像库配置
        for (Map.Entry<String, MappingConfig> entry : rdbMapping.entrySet()) {
            String configName = entry.getKey();
            MappingConfig mappingConfig = entry.getValue();
            //不是镜像库
            if (!mappingConfig.getDbMapping().getMirrorDb()) {
                String key;
                //不是tpc模式的，如kafka，rabbitMQ,racketMQ模式，key=canal实例名-groupId_database-table
                if (envProperties != null && !"tcp".equalsIgnoreCase(envProperties.getProperty("canal.conf.mode"))) {
                    key = StringUtils.trimToEmpty(mappingConfig.getDestination()) + "-"
                          + StringUtils.trimToEmpty(mappingConfig.getGroupId()) + "_"
                          + mappingConfig.getDbMapping().getDatabase() + "-" + mappingConfig.getDbMapping().getTable();
                } else {
                    //tcp模式，key=canal实例名_database-table
                    key = StringUtils.trimToEmpty(mappingConfig.getDestination()) + "_"
                          + mappingConfig.getDbMapping().getDatabase() + "-" + mappingConfig.getDbMapping().getTable();
                }
                //不存在则设置一个空map，然后往map中填值
                Map<String, MappingConfig> configMap = mappingConfigCache.computeIfAbsent(key,
                    k1 -> new ConcurrentHashMap<>());
                configMap.put(configName, mappingConfig);
            } else {
                // mirrorDB，key=canal实例名.database
                String key = StringUtils.trimToEmpty(mappingConfig.getDestination()) + "."
                             + mappingConfig.getDbMapping().getDatabase();
                mirrorDbConfigCache.put(key, MirrorDbConfig.create(configName, mappingConfig));
            }
        }

        // 4.初始化连接池
        Map<String, String> properties = configuration.getProperties();
        dataSource = new DruidDataSource();
        dataSource.setDriverClassName(properties.get("jdbc.driverClassName"));
        dataSource.setUrl(properties.get("jdbc.url"));
        dataSource.setUsername(properties.get("jdbc.username"));
        dataSource.setPassword(properties.get("jdbc.password"));
        dataSource.setInitialSize(1);
        dataSource.setMinIdle(1);
        dataSource.setMaxActive(30);
        dataSource.setMaxWait(60000);
        dataSource.setTimeBetweenEvictionRunsMillis(60000);
        dataSource.setMinEvictableIdleTimeMillis(300000);
        dataSource.setUseUnfairLock(true);
        // List<String> array = new ArrayList<>();
        // array.add("set names utf8mb4;");
        // dataSource.setConnectionInitSqls(array);

        try {
            dataSource.init();
        } catch (SQLException e) {
            logger.error("ERROR ## failed to initial datasource: " + properties.get("jdbc.url"), e);
        }
        //并行执行的线程数, 默认为1
        String threads = properties.get("threads");
        // String commitSize = properties.get("commitSize");

        boolean skipDupException = BooleanUtils.toBoolean(configuration.getProperties()
            .getOrDefault("skipDupException", "true"));

        //初始化RDB同步操作业务服务
        rdbSyncService = new RdbSyncService(dataSource,
            threads != null ? Integer.valueOf(threads) : null,
            skipDupException);

        //初始化RDB镜像库同步操作业务服务
        rdbMirrorDbSyncService = new RdbMirrorDbSyncService(mirrorDbConfigCache,
            dataSource,
            threads != null ? Integer.valueOf(threads) : null,
            rdbSyncService.getColumnsTypeCache(),
            skipDupException);
        //开启对rdb配置文件的监控，监控目录/conf/rdb/xx.yml,其次为classpath:rdb/xx.yml
        rdbConfigMonitor = new RdbConfigMonitor();
        rdbConfigMonitor.init(configuration.getKey(), this, envProperties);
    }

    /**
     * 同步方法
     *
     * @param dmls 数据包
     */
    @Override
    public void sync(List<Dml> dmls) {
        if (dmls == null || dmls.isEmpty()) {
            return;
        }
        try {
            rdbSyncService.sync(mappingConfigCache, dmls, envProperties);
            rdbMirrorDbSyncService.sync(dmls);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * ETL方法
     *
     * @param task 任务名, 对应配置名
     * @param params etl筛选条件
     * @return ETL结果
     */
    @Override
    public EtlResult etl(String task, List<String> params) {
        EtlResult etlResult = new EtlResult();
        MappingConfig config = rdbMapping.get(task);
        RdbEtlService rdbEtlService = new RdbEtlService(dataSource, config);
        if (config != null) {
            return rdbEtlService.importData(params);
        } else {
            StringBuilder resultMsg = new StringBuilder();
            boolean resSucc = true;
            for (MappingConfig configTmp : rdbMapping.values()) {
                // 取所有的destination为task的配置
                if (configTmp.getDestination().equals(task)) {
                    EtlResult etlRes = rdbEtlService.importData(params);
                    if (!etlRes.getSucceeded()) {
                        resSucc = false;
                        resultMsg.append(etlRes.getErrorMessage()).append("\n");
                    } else {
                        resultMsg.append(etlRes.getResultMessage()).append("\n");
                    }
                }
            }
            if (resultMsg.length() > 0) {
                etlResult.setSucceeded(resSucc);
                if (resSucc) {
                    etlResult.setResultMessage(resultMsg.toString());
                } else {
                    etlResult.setErrorMessage(resultMsg.toString());
                }
                return etlResult;
            }
        }
        etlResult.setSucceeded(false);
        etlResult.setErrorMessage("Task not found");
        return etlResult;
    }

    /**
     * 获取总数方法
     *
     * @param task 任务名, 对应配置名
     * @return 总数
     */
    @Override
    public Map<String, Object> count(String task) {
        MappingConfig config = rdbMapping.get(task);
        MappingConfig.DbMapping dbMapping = config.getDbMapping();
        String sql = "SELECT COUNT(1) AS cnt FROM " + SyncUtil.getDbTableName(dbMapping);
        Connection conn = null;
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            conn = dataSource.getConnection();
            Util.sqlRS(conn, sql, rs -> {
                try {
                    if (rs.next()) {
                        Long rowCount = rs.getLong("cnt");
                        res.put("count", rowCount);
                    }
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                }
            });
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        res.put("targetTable", SyncUtil.getDbTableName(dbMapping));

        return res;
    }

    /**
     * 获取对应canal instance name 或 mq topic
     *
     * @param task 任务名, 对应配置名
     * @return destination
     */
    @Override
    public String getDestination(String task) {
        MappingConfig config = rdbMapping.get(task);
        if (config != null) {
            return config.getDestination();
        }
        return null;
    }

    /**
     * 销毁方法
     */
    @Override
    public void destroy() {
        if (rdbConfigMonitor != null) {
            rdbConfigMonitor.destroy();
        }

        if (rdbSyncService != null) {
            rdbSyncService.close();
        }

        if (dataSource != null) {
            dataSource.close();
        }
    }
}
