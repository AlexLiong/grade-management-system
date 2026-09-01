package edu.chd.practice.web.dto;

public final class OcrDtos {
    private OcrDtos() {
    }

    public record Recognition(String text, String provider, String requestId) {
    }
}
