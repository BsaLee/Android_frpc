package com.frpc.launcher;

/**
 * 配置常量类
 * 统一管理所有默认配置值
 */
public class ConfigConstants {
    // 服务器配置默认值
    public static final String DEFAULT_SERVER_ADDR = "1.2.3.4";
    public static final int DEFAULT_SERVER_PORT = 1024;
    public static final String DEFAULT_AUTH_TOKEN = "1024";
    public static final int DEFAULT_LOCAL_PORT = 5555;
    
    // 随机端口范围默认值
    public static final int DEFAULT_RANDOM_PORT_MIN = 60000;
    public static final int DEFAULT_RANDOM_PORT_MAX = 65535;
    
    private ConfigConstants() {
        // 工具类，不允许实例化
    }
}

