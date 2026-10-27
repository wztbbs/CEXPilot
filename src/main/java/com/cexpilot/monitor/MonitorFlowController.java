package com.cexpilot.monitor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static com.cexpilot.monitor.MonitorModels.*;

/** 浏览器访客隔离的灰度接口；同源 JSON + 自定义头用于阻止跨站表单确认。 */
@RestController
@RequestMapping("/api/monitor-flow")
public class MonitorFlowController {
    private final MonitorConversationFlow flow;
    public MonitorFlowController(MonitorConversationFlow flow) { this.flow=flow; }
    public record EmailRequest(String email) {}
    public record Confirmation(int version,String specHash,String recipient) {}
    @GetMapping("/profile") public Object profile(@CookieValue(value="cexpilot_uid",required=false) String visitor) { return flow.profile(visitor); }
    @PutMapping("/profile") public Object email(@CookieValue(value="cexpilot_uid",required=false) String visitor,
            @RequestHeader("X-Monitor-Action") String action,@RequestBody EmailRequest request) {
        check(action); return flow.bindEmail(visitor,request.email());
    }
    @GetMapping("/rules") public Object list(@CookieValue(value="cexpilot_uid",required=false) String visitor) { return flow.list(visitor); }
    @GetMapping("/rules/{id}") public Object detail(@CookieValue(value="cexpilot_uid",required=false) String visitor,
            @PathVariable String id,@RequestParam(required=false) Integer version) { return flow.card(visitor,id,version); }
    @GetMapping("/rules/{id}/executions") public Object executions(@CookieValue(value="cexpilot_uid",required=false) String visitor,@PathVariable String id) {
        return flow.executions(visitor,id);
    }
    @GetMapping("/rules/{id}/events") public Object events(@CookieValue(value="cexpilot_uid",required=false) String visitor,@PathVariable String id) {
        return flow.events(visitor,id);
    }
    @PostMapping("/rules/{id}/confirm") public Object confirm(@CookieValue(value="cexpilot_uid",required=false) String visitor,
            @PathVariable String id,@RequestHeader("X-Monitor-Action") String action,@RequestBody Confirmation request) {
        check(action); return flow.confirm(visitor,id,new ConfirmRequest(request.version(),request.specHash()),request.recipient());
    }
    @PostMapping("/rules/{id}/stop") public Object stop(@CookieValue(value="cexpilot_uid",required=false) String visitor,
            @PathVariable String id,@RequestHeader("X-Monitor-Action") String action,@RequestBody ConfirmRequest request) {
        check(action); flow.stop(visitor,id,request); return Map.of("stopped",true);
    }
    @PostMapping("/rules/{id}/reject") public Object reject(@CookieValue(value="cexpilot_uid",required=false) String visitor,
            @PathVariable String id,@RequestHeader("X-Monitor-Action") String action,@RequestBody ConfirmRequest request) {
        check(action); flow.reject(visitor,id,request); return Map.of("rejected",true);
    }
    private void check(String action) { if(!"1".equals(action)) throw new IllegalArgumentException("无效操作请求"); }
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()));
    }
}
