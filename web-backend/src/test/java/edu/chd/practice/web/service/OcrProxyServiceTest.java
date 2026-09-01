package edu.chd.practice.web.service;

import edu.chd.practice.web.config.OcrProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrProxyServiceTest {
    @Test
    void onlyHttpsProviderUrlsWithAHostAreConfigured() {
        assertThat(ocrProperties("https://ocr.example.test/recognize", "token").configured()).isTrue();
        assertThat(ocrProperties("HTTP://ocr.example.test/recognize", "token").configured()).isFalse();
        assertThat(ocrProperties("https:/recognize", "token").configured()).isFalse();
        assertThat(ocrProperties("https://ocr.example.test/recognize", " ").configured()).isFalse();
    }

    @Test
    void multipartThresholdKeepsEveryAcceptedImageInMemory() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();

        assertThat(properties).isNotNull();
        DataSize threshold = DataSize.parse(properties.getProperty(
                "spring.servlet.multipart.file-size-threshold"));
        DataSize maximum = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-file-size"));
        assertThat(threshold.toBytes()).isGreaterThan(maximum.toBytes());
    }

    @Test
    void appliesConfiguredTimeoutToConnectAndReadOperations() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.requestFactory(any(ClientHttpRequestFactory.class))).thenReturn(builder);
        when(builder.build()).thenReturn(mock(RestClient.class));
        OcrProperties properties = new OcrProperties(URI.create("https://ocr.example.test/recognize"),
                "token", 1024, Duration.ofMillis(1250), Set.of("image/png"));

        new OcrProxyService(properties, mock(ResourceAccessService.class), builder);

        ArgumentCaptor<ClientHttpRequestFactory> requestFactory =
                ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(builder).requestFactory(requestFactory.capture());
        assertThat(requestFactory.getValue()).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(requestFactory.getValue(), "connectTimeout")).isEqualTo(1250);
        assertThat(ReflectionTestUtils.getField(requestFactory.getValue(), "readTimeout")).isEqualTo(1250);
    }

    private OcrProperties ocrProperties(String url, String token) {
        return new OcrProperties(URI.create(url), token, 1024,
                Duration.ofSeconds(1), Set.of("image/png"));
    }
}
