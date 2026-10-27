package com.cexpilot.monitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class MonitorAccessFilter extends OncePerRequestFilter {
    private final MonitorConfig config;
    public MonitorAccessFilter(MonitorConfig config) { this.config=config; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !health(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control","no-store");
        if (config.getAccessToken().length()<32) {
            error(response,503,"巡检访问凭证未配置"); return;
        }
        String header = request.getHeader("Authorization");
        if (header==null || !header.startsWith("Bearer ") || !MessageDigest.isEqual(
                config.getAccessToken().getBytes(StandardCharsets.UTF_8),header.substring(7).getBytes(StandardCharsets.UTF_8))) {
            error(response,401,"巡检访问凭证无效"); return;
        }
        chain.doFilter(request,response);
    }
    private boolean health(String path) { return path.equals("/api/monitor-health") || path.startsWith("/api/monitor-health/"); }
    private void error(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\""+message+"\"}");
    }
}
