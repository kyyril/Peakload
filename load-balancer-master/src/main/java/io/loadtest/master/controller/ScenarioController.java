package io.loadtest.master.controller;

import io.loadtest.master.dto.CreateScenarioRequest;
import io.loadtest.master.dto.ScenarioResponse;
import io.loadtest.master.dto.ScenarioStatus;
import io.loadtest.master.dto.StartTestRequest;
import io.loadtest.master.dto.TestResultSummary;
import io.loadtest.master.service.ScenarioOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for load test scenario management.
 *
 * API Endpoints:
 * ─────────────────────────────────────────────────────────────────
 * POST   /scenarios                     Create new test scenario
 * GET    /scenarios                     List all scenarios
 * GET    /scenarios/{id}                Get scenario details
 * DELETE /scenarios/{id}                Delete scenario
 *
 * POST   /scenarios/{id}/start          Start the test
 * POST   /scenarios/{id}/stop           Stop the test
 * GET    /scenarios/{id}/status         Get current status
 * GET    /scenarios/{id}/metrics        Get live metrics (SSE)
 * GET    /scenarios/{id}/results        Get final results
 *
 * GET    /workers                        List connected workers
 * GET    /workers/{id}/health           Get worker health
 * ─────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/v1/scenarios")
public class ScenarioController {

    private final ScenarioOrchestrationService orchestrationService;

    public ScenarioController(ScenarioOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    // ============================================================
    // SCENARIO CRUD
    // ============================================================

    @PostMapping
    public ResponseEntity<ScenarioResponse> createScenario(
            @Valid @RequestBody CreateScenarioRequest request
    ) {
        ScenarioResponse scenario = orchestrationService.createScenario(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(scenario);
    }

    @GetMapping
    public ResponseEntity<List<ScenarioResponse>> listScenarios(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status
    ) {
        List<ScenarioResponse> scenarios = orchestrationService.listScenarios(page, size, status);
        return ResponseEntity.ok(scenarios);
    }

    @GetMapping("/{scenarioId}")
    public ResponseEntity<ScenarioResponse> getScenario(
            @PathVariable String scenarioId
    ) {
        return orchestrationService.getScenario(scenarioId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<Void> deleteScenario(
            @PathVariable String scenarioId
    ) {
        orchestrationService.deleteScenario(scenarioId);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // TEST EXECUTION CONTROL
    // ============================================================

    @PostMapping("/{scenarioId}/start")
    public ResponseEntity<ScenarioStatus> startTest(
            @PathVariable String scenarioId,
            @RequestBody(required = false) StartTestRequest request
    ) {
        ScenarioStatus status = orchestrationService.startTest(scenarioId, request);
        return ResponseEntity.accepted().body(status);
    }

    @PostMapping("/{scenarioId}/stop")
    public ResponseEntity<ScenarioStatus> stopTest(
            @PathVariable String scenarioId,
            @RequestParam(defaultValue = "false") boolean immediate
    ) {
        ScenarioStatus status = orchestrationService.stopTest(scenarioId, immediate);
        return ResponseEntity.accepted().body(status);
    }

    @GetMapping("/{scenarioId}/status")
    public ResponseEntity<ScenarioStatus> getStatus(
            @PathVariable String scenarioId
    ) {
        return orchestrationService.getStatus(scenarioId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{scenarioId}/results")
    public ResponseEntity<TestResultSummary> getResults(
            @PathVariable String scenarioId
    ) {
        return orchestrationService.getResults(scenarioId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ============================================================
    // WORKER MANAGEMENT
    // ============================================================

    @GetMapping("/workers")
    public ResponseEntity<Object> listWorkers() {
        return ResponseEntity.ok(orchestrationService.listWorkers());
    }

    @GetMapping("/workers/{workerId}/health")
    public ResponseEntity<Object> getWorkerHealth(
            @PathVariable String workerId
    ) {
        return orchestrationService.getWorkerHealth(workerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
