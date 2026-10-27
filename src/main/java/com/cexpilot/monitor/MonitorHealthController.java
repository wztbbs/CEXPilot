package com.cexpilot.monitor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
public class MonitorHealthController {
    private final MonitorHealthService service;
    public MonitorHealthController(MonitorHealthService service) { this.service=service; }
    @GetMapping("/api/monitor-health")
    public ResponseEntity<MonitorHealthReport> health(@RequestParam(defaultValue="24") int sinceHours,@RequestParam(required=false) String ruleId) {
        var report=service.inspect(sinceHours,ruleId);
        return ResponseEntity.status(report.status().equals("INCOMPLETE")?503:200).header("Cache-Control","no-store").body(report);
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("error",error.getMessage())); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason())); }
}
