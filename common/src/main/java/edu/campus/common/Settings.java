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
            loaded = true;
        }
    }

    /**
     * Read a configuration value: environment variable > system property > Spring properties.
     */
    public static String get(String key) {
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
     * Runtime directory for this module (config, certs, database, ledger).
     */
    public static Path root() {
        return Path.of(".runtime").toAbsolutePath();
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
