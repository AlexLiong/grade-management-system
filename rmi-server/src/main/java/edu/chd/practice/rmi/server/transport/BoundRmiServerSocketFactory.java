package edu.chd.practice.rmi.server.transport;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.server.RMIServerSocketFactory;
import java.util.Objects;

public final class BoundRmiServerSocketFactory implements RMIServerSocketFactory, Serializable {
    private static final long serialVersionUID = 1L;
    private final String bindAddress;

    public BoundRmiServerSocketFactory(String bindAddress) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port, 50, InetAddress.getByName(bindAddress));
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof BoundRmiServerSocketFactory other && bindAddress.equals(other.bindAddress);
    }

    @Override
    public int hashCode() {
        return bindAddress.hashCode();
    }
}
