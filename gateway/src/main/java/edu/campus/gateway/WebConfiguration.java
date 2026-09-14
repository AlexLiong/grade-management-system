package edu.campus.gateway;

import edu.campus.common.Settings;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

  @Component
  public static class Headers extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
        throws ServletException, IOException {
      s.setHeader(
          "Content-Security-Policy",
          "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' blob:; worker-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
      s.setHeader("X-Content-Type-Options", "nosniff");
      s.setHeader("Referrer-Policy", "no-referrer");
      s.setHeader("X-Frame-Options", "DENY");
      s.setHeader("Strict-Transport-Security", "max-age=31536000");
      s.setHeader("Cache-Control", "no-store");
      chain.doFilter(r, s);
    }
  }
}
