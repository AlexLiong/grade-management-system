package edu.campus.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

public final class Settings {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static volatile boolean loaded = false;
    private static final String PREFIX = "campus.";
    private static Environment env;

    private Settings() {
    }

    @Component
    static class Loader {
        @Autowired
        void init(Environment e) {
            env = e;
            // 兜底加载（必要时生成）密钥并注入系统属性：DatabaseBootstrap / EnvironmentPostProcessor
            // 已经在更早的阶段调用过，这里是幂等的第二次保险。
            ConfigGuard.load();
            loaded = true;
        }
    }

    /**
     * Read a configuration value: environment variable > system property > Spring properties.
     *
     * <p>首次取值前会确保密钥已加载/注入：这样即使调用点早于 {@link Loader} 的执行
     * （例如数据源初始化），也不会取到空值或占位值。
     */
    public static String get(String key) {
        if (!loaded && !ConfigGuard.isLoaded()) ConfigGuard.load();
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) return envVal;
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        if (Settings.env != null) {
            String springVal = Settings.env.getProperty(PREFIX+key);
            if (springVal != null && !springVal.isBlank()) return springVal;
        }
        throw new IllegalStateException("Missing setting: " + key);
    }

    /**
     * 运行时目录（配置、证书、数据库、账本）。
     *
     * <p>刻意不依赖密钥加载：密钥文件本身也要落在这个目录下，若这里先要求密钥会形成循环。
     */
    public static Path root() {
        String configured = System.getenv("CAMPUS_RUNTIME");
        if (configured == null || configured.isBlank()) configured = System.getProperty("campus.runtime");
        return configured != null && !configured.isBlank()
                ? Path.of(configured).toAbsolutePath()
                : Path.of(".runtime").toAbsolutePath();
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
