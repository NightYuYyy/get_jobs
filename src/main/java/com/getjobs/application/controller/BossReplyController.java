package com.getjobs.application.controller;

import com.getjobs.application.service.BossReplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/boss/replies")
@CrossOrigin(origins = {"http://127.0.0.1:9527", "http://localhost:9527",
        "http://127.0.0.1:6866", "http://localhost:6866", "http://localhost:3000", "http://127.0.0.1:3000"})
@RequiredArgsConstructor
public class BossReplyController {
    private final BossReplyService service;
    public record Start(boolean autoSend) {}
    public record Send(String answer) {}

    @GetMapping
    public ResponseEntity<?> status(HttpServletRequest request) {
        return execute(request, service::status);
    }
    @PostMapping("/start")
    public ResponseEntity<?> start(HttpServletRequest request, @RequestBody Start body) {
        return execute(request, () -> { service.start(body.autoSend()); return service.status(); });
    }
    @PostMapping("/stop")
    public ResponseEntity<?> stop(HttpServletRequest request) {
        return execute(request, () -> { service.stop(); return service.status(); });
    }
    @PostMapping("/{id}/send")
    public ResponseEntity<?> send(HttpServletRequest request, @PathVariable String id, @RequestBody Send body) {
        return execute(request, () -> { service.send(id, body.answer()); return service.status(); });
    }
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<?> dismiss(HttpServletRequest request, @PathVariable String id) {
        return execute(request, () -> { service.dismiss(id); return service.status(); });
    }
    @PostMapping("/sessions/{key}/pause")
    public ResponseEntity<?> pause(HttpServletRequest request, @PathVariable String key) {
        return execute(request, () -> { service.pause(key); return service.status(); });
    }
    @PostMapping("/sessions/{key}/resume")
    public ResponseEntity<?> resume(HttpServletRequest request, @PathVariable String key) {
        return execute(request, () -> { service.resume(key); return service.status(); });
    }

    public static boolean allowedOrigin(String remote, String origin, String fetchSite) {
        if (remote == null || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote)) return false;
        if (origin == null) return !"cross-site".equals(fetchSite);
        try {
            var uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost())
                    && Set.of(9527, 6866, 3000).contains(uri.getPort())
                    && uri.getUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }

    private ResponseEntity<?> execute(HttpServletRequest request, Supplier<Object> action) {
        if (!allowedOrigin(request.getRemoteAddr(), request.getHeader("Origin"), request.getHeader("Sec-Fetch-Site"))) {
            return ResponseEntity.status(403).body(Map.of("message", "回复模块仅允许本机管理页面访问"));
        }
        try { return ResponseEntity.ok(action.get()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("message", e.getMessage())); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("message", "回复操作失败，请检查本机日志")); }
    }
}
