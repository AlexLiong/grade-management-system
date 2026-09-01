package edu.chd.practice.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import edu.chd.practice.web.config.OcrProperties;
import edu.chd.practice.web.dto.OcrDtos;
import edu.chd.practice.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;

@Service
public class OcrProxyService {
    private final OcrProperties properties;
    private final ResourceAccessService access;
    private final RestClient restClient;

    public OcrProxyService(OcrProperties properties, ResourceAccessService access, RestClient.Builder builder) {
        this.properties = properties;
        this.access = access;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, properties.timeout().toMillis()));
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    public OcrDtos.Recognition recognize(MultipartFile image, HttpServletRequest request) {
        access.requirePermission("GRADE_DRAFT_WRITE");
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OCR_NOT_CONFIGURED",
                    "OCR 服务未配置，请设置 OCR_API_URL 和 OCR_API_TOKEN");
        }
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OCR_IMAGE_REQUIRED", "请选择成绩图片");
        }
        if (image.getSize() > properties.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "OCR_IMAGE_TOO_LARGE", "图片超过允许大小");
        }
        String contentType = image.getContentType();
        if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "OCR_IMAGE_TYPE_INVALID",
                    "仅支持 JPEG、PNG 和 WebP 图片");
        }
        byte[] bytes = null;
        try {
            bytes = image.getBytes();
            if (!matchesSignature(contentType, bytes)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "OCR_IMAGE_SIGNATURE_INVALID",
                        "图片内容与声明类型不一致");
            }
            JsonNode response = restClient.post().uri(properties.apiUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                    .contentType(MediaType.parseMediaType(contentType))
                    .accept(MediaType.APPLICATION_JSON)
                    .body(bytes)
                    .retrieve().body(JsonNode.class);
            String text = extractText(response);
            if (text == null || text.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OCR_RESPONSE_INVALID", "OCR 服务未返回可识别文本");
            }
            Object traceId = request.getAttribute("traceId");
            return new OcrDtos.Recognition(text, properties.apiUrl().getHost(),
                    traceId == null ? "unknown" : traceId.toString());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OCR_IMAGE_READ_FAILED", "无法读取图片", exception);
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OCR_PROVIDER_FAILED", "OCR 服务调用失败", exception);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) return null;
        if (response.path("text").isTextual()) return response.path("text").asText();
        if (response.path("result").isTextual()) return response.path("result").asText();
        if (response.path("data").path("text").isTextual()) return response.path("data").path("text").asText();
        return null;
    }

    private boolean matchesSignature(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                    && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                    && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }
}
