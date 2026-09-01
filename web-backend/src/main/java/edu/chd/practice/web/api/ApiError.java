package edu.chd.practice.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, String traceId, Map<String, String> fieldErrors) {
}
