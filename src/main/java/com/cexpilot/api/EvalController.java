package com.cexpilot.api;

import com.cexpilot.eval.EvalRepository;
import com.cexpilot.eval.EvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalRepository evalRepository;

    public EvalController(EvalService evalService, EvalRepository evalRepository) {
        this.evalService = evalService;
        this.evalRepository = evalRepository;
    }

    /** 跑一轮 Smoke Eval。category 可选：market / ethereum / conversation，不传跑全部。 */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestParam(required = false) String category) {
        try {
            return ResponseEntity.ok(evalService.run(category));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/run/{runId}")
    public ResponseEntity<?> runDetail(@PathVariable String runId) {
        Map<String, Object> run = evalRepository.findRun(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("run", run, "results", evalRepository.findResults(runId)));
    }
}
