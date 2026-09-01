package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.dto.OcrDtos;
import edu.chd.practice.web.service.OcrProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ocr")
public class OcrController {
    private final OcrProxyService service;

    public OcrController(OcrProxyService service) {
        this.service = service;
    }

    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OcrDtos.Recognition> recognize(@RequestParam("image") MultipartFile image,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(service.recognize(image, request));
    }
}
