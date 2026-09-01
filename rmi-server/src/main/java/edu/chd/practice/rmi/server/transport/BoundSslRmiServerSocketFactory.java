package edu.chd.practice.rmi.server.transport;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Objects;

public final class BoundSslRmiServerSocketFactory extends SslRMIServerSocketFactory {
    private static final long serialVersionUID = 1L;
    private final String bindAddress;
    private final boolean needClientAuth;

    public BoundSslRmiServerSocketFactory(String bindAddress, boolean needClientAuth) {
        super(null, null, needClientAuth);
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.needClientAuth = needClientAuth;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) SSLServerSocketFactory.getDefault()
                .createServerSocket(port, 50, InetAddress.getByName(bindAddress));
        socket.setNeedClientAuth(needClientAuth);
        return socket;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof BoundSslRmiServerSocketFactory other
                && bindAddress.equals(other.bindAddress) && needClientAuth == other.needClientAuth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindAddress, needClientAuth);
    }
}
