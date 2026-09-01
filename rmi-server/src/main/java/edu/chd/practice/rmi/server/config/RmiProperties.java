package edu.chd.practice.rmi.server.config;

import edu.chd.practice.rmi.contract.RmiBindings;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rmi")
public class RmiProperties {
    private String host = "127.0.0.1";
    private String bindAddress = "127.0.0.1";
    private int registryPort = RmiBindings.DEFAULT_REGISTRY_PORT;
    private int servicePort = RmiBindings.DEFAULT_SERVICE_PORT;
    private boolean tlsEnabled;
    private boolean tlsRequired;
    private boolean tlsNeedClientAuth = true;
    private String hmacSecret;
    private String hmacKeyFile = "runtime/rmi-hmac.key";
    private int clientConnectTimeoutMillis = 5_000;
    private int clientReadTimeoutMillis = 5_000;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public int getRegistryPort() { return registryPort; }
    public void setRegistryPort(int registryPort) { this.registryPort = registryPort; }
    public int getServicePort() { return servicePort; }
    public void setServicePort(int servicePort) { this.servicePort = servicePort; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }
    public boolean isTlsRequired() { return tlsRequired; }
    public void setTlsRequired(boolean tlsRequired) { this.tlsRequired = tlsRequired; }
    public boolean isTlsNeedClientAuth() { return tlsNeedClientAuth; }
    public void setTlsNeedClientAuth(boolean tlsNeedClientAuth) { this.tlsNeedClientAuth = tlsNeedClientAuth; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public String getHmacKeyFile() { return hmacKeyFile; }
    public void setHmacKeyFile(String hmacKeyFile) { this.hmacKeyFile = hmacKeyFile; }
    public int getClientConnectTimeoutMillis() { return clientConnectTimeoutMillis; }
    public void setClientConnectTimeoutMillis(int clientConnectTimeoutMillis) {
        this.clientConnectTimeoutMillis = clientConnectTimeoutMillis;
    }
    public int getClientReadTimeoutMillis() { return clientReadTimeoutMillis; }
    public void setClientReadTimeoutMillis(int clientReadTimeoutMillis) {
        this.clientReadTimeoutMillis = clientReadTimeoutMillis;
    }
}
