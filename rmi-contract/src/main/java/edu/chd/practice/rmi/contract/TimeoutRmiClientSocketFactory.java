package edu.chd.practice.rmi.contract;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.util.Objects;

public final class TimeoutRmiClientSocketFactory implements RMIClientSocketFactory, Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean tls;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public TimeoutRmiClientSocketFactory(boolean tls, int connectTimeoutMillis, int readTimeoutMillis) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("RMI connect and read timeouts must be positive");
        }
        this.tls = tls;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            if (socket instanceof SSLSocket sslSocket) {
                SSLParameters parameters = sslSocket.getSSLParameters();
                enableEndpointIdentification(parameters);
                sslSocket.setSSLParameters(parameters);
            }
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the connection failure.
            }
            throw exception;
        }
    }

    public boolean isTls() {
        return tls;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    static void enableEndpointIdentification(SSLParameters parameters) {
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TimeoutRmiClientSocketFactory that)) return false;
        return tls == that.tls && connectTimeoutMillis == that.connectTimeoutMillis
                && readTimeoutMillis == that.readTimeoutMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tls, connectTimeoutMillis, readTimeoutMillis);
    }
}
