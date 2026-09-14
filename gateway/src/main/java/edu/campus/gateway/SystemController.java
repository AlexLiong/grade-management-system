package edu.campus.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

@RestController
public class SystemController {

  @GetMapping("/system")
  public String systemPage() throws IOException {
    ClassPathResource resource = new ClassPathResource("system/index.html");
    try (InputStream is = resource.getInputStream()) {
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
