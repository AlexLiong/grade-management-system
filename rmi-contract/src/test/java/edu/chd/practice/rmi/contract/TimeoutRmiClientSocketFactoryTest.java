package edu.chd.practice.rmi.contract;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeoutRmiClientSocketFactoryTest {
    @Test
    void tlsSocketsUseHttpsHostnameVerification() {
        SSLParameters parameters = new SSLParameters();

        TimeoutRmiClientSocketFactory.enableEndpointIdentification(parameters);

        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());
    }
}
