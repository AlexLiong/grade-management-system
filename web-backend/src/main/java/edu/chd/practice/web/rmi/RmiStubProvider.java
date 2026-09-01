package edu.chd.practice.web.rmi;

import edu.chd.practice.rmi.contract.HealthInterface;
import edu.chd.practice.rmi.contract.IntegrityInterface;
import edu.chd.practice.rmi.contract.ManipulationInterface;
import edu.chd.practice.rmi.contract.RmiBindings;
import edu.chd.practice.rmi.contract.SelectInterface;
import edu.chd.practice.rmi.contract.TimeoutRmiClientSocketFactory;
import edu.chd.practice.web.config.RmiProperties;
import edu.chd.practice.web.error.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;

@Component
public class RmiStubProvider {
    private final RmiProperties properties;
    private volatile Stubs stubs;

    public RmiStubProvider(RmiProperties properties, Environment environment) {
        this.properties = properties;
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (production && !properties.tlsEnabled()) {
            throw new IllegalStateException("Production profile requires rmi.tls-enabled=true");
        }
        if (production && (properties.hmacSecret() == null || properties.hmacSecret().isBlank())
                && !properties.hmacKeyFile().isAbsolute()) {
            // Relative runtime key files are intentional for local development but ambiguous in a deployment.
            throw new IllegalStateException("Production requires RMI_HMAC_SECRET or an absolute RMI_HMAC_KEY_FILE");
        }
    }

    public SelectInterface select() {
        return current().select();
    }

    public ManipulationInterface manipulation() {
        return current().manipulation();
    }

    public HealthInterface health() {
        return current().health();
    }

    public IntegrityInterface integrity() {
        return current().integrity();
    }

    public void invalidate() {
        stubs = null;
    }

    private Stubs current() {
        Stubs local = stubs;
        if (local == null) {
            synchronized (this) {
                local = stubs;
                if (local == null) {
                    stubs = local = lookup();
                }
            }
        }
        return local;
    }

    private Stubs lookup() {
        try {
            long configuredTimeoutMillis = properties.timeout().toMillis();
            if (configuredTimeoutMillis < 1 || configuredTimeoutMillis > Integer.MAX_VALUE) {
                throw new IllegalStateException("rmi.timeout must be between 1 ms and "
                        + Integer.MAX_VALUE + " ms");
            }
            int timeoutMillis = (int) configuredTimeoutMillis;
            Registry registry = LocateRegistry.getRegistry(properties.host(), properties.port(),
                    new TimeoutRmiClientSocketFactory(properties.tlsEnabled(), timeoutMillis, timeoutMillis));
            return new Stubs((SelectInterface) registry.lookup(RmiBindings.SELECT),
                    (ManipulationInterface) registry.lookup(RmiBindings.MANIPULATION),
                    (HealthInterface) registry.lookup(RmiBindings.HEALTH),
                    (IntegrityInterface) registry.lookup(RmiBindings.INTEGRITY));
        } catch (NotBoundException exception) {
            throw unavailable("RMI 服务绑定不完整", exception);
        } catch (RemoteException exception) {
            String message = causedBy(exception, SSLHandshakeException.class)
                    ? "RMI TLS 握手失败" : "RMI 数据服务不可用";
            throw unavailable(message, exception);
        }
    }

    private boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private ApiException unavailable(String message, Exception exception) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RMI_UNAVAILABLE", message, exception);
    }

    private record Stubs(SelectInterface select, ManipulationInterface manipulation,
                         HealthInterface health, IntegrityInterface integrity) {
    }
}
