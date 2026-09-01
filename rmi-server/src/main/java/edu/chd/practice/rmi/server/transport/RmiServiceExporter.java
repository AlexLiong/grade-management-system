package edu.chd.practice.rmi.server.transport;

import edu.chd.practice.rmi.contract.HealthInterface;
import edu.chd.practice.rmi.contract.IntegrityInterface;
import edu.chd.practice.rmi.contract.ManipulationInterface;
import edu.chd.practice.rmi.contract.RmiBindings;
import edu.chd.practice.rmi.contract.SelectInterface;
import edu.chd.practice.rmi.contract.TimeoutRmiClientSocketFactory;
import edu.chd.practice.rmi.server.config.RmiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ObjectInputFilter;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RmiServiceExporter implements ApplicationRunner, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RmiServiceExporter.class);
    private static final String SERIAL_FILTER = "maxdepth=24;maxrefs=10000;maxbytes=1048576;"
            + "java.lang.*;java.util.*;java.time.*;java.math.*;"
            + "edu.chd.practice.rmi.contract.**;!*";

    private final RmiProperties properties;
    private final SelectInterface select;
    private final ManipulationInterface manipulation;
    private final HealthInterface health;
    private final IntegrityInterface integrity;
    private final List<Remote> exported = new ArrayList<>();
    private Registry registry;

    public RmiServiceExporter(RmiProperties properties, SelectInterface select,
                              ManipulationInterface manipulation, HealthInterface health,
                              IntegrityInterface integrity) {
        this.properties = properties;
        this.select = select;
        this.manipulation = manipulation;
        this.health = health;
        this.integrity = integrity;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (properties.isTlsRequired() && !properties.isTlsEnabled()) {
            throw new IllegalStateException("RMI TLS is required by this profile but rmi.tls-enabled=false");
        }
        System.setProperty("java.rmi.server.hostname", properties.getHost());
        System.setProperty("java.rmi.server.randomIDs", "true");
        System.setProperty("sun.rmi.registry.registryFilter", SERIAL_FILTER);

        RMIClientSocketFactory clientFactory = new TimeoutRmiClientSocketFactory(
                properties.isTlsEnabled(), properties.getClientConnectTimeoutMillis(),
                properties.getClientReadTimeoutMillis());
        RMIServerSocketFactory serverFactory = properties.isTlsEnabled()
                ? new BoundSslRmiServerSocketFactory(properties.getBindAddress(),
                        properties.isTlsNeedClientAuth())
                : new BoundRmiServerSocketFactory(properties.getBindAddress());
        ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(SERIAL_FILTER);

        registry = LocateRegistry.createRegistry(properties.getRegistryPort(), clientFactory, serverFactory);
        bind(RmiBindings.SELECT, select, clientFactory, serverFactory, filter);
        bind(RmiBindings.MANIPULATION, manipulation, clientFactory, serverFactory, filter);
        bind(RmiBindings.HEALTH, health, clientFactory, serverFactory, filter);
        bind(RmiBindings.INTEGRITY, integrity, clientFactory, serverFactory, filter);
        log.info("RMI data services ready at {}:{} (service port {}, TLS={})",
                properties.getHost(), properties.getRegistryPort(), properties.getServicePort(),
                properties.isTlsEnabled());
    }

    private void bind(String name, Remote service, RMIClientSocketFactory clientFactory,
                      RMIServerSocketFactory serverFactory, ObjectInputFilter filter) throws Exception {
        Remote stub = UnicastRemoteObject.exportObject(service, properties.getServicePort(),
                clientFactory, serverFactory, filter);
        exported.add(service);
        registry.rebind(name, stub);
    }

    @Override
    public void close() {
        if (registry != null) {
            for (String name : List.of(RmiBindings.SELECT, RmiBindings.MANIPULATION,
                    RmiBindings.HEALTH, RmiBindings.INTEGRITY)) {
                try { registry.unbind(name); } catch (Exception ignored) { }
            }
        }
        for (Remote service : exported) {
            try { UnicastRemoteObject.unexportObject(service, true); } catch (Exception ignored) { }
        }
        if (registry != null) {
            try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { }
        }
        exported.clear();
        registry = null;
    }
}
