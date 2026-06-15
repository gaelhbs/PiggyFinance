package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.service.GoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse create(@RequestBody @Valid CreateGoalRequest request,
                               @AuthenticationPrincipal UUID userId) {
        return goalService.create(request, userId);
    }

    @GetMapping
    public List<GoalResponse> list(@AuthenticationPrincipal UUID userId) {
        return goalService.list(userId);
    }

    @PutMapping("/{id}")
    public GoalResponse update(@PathVariable UUID id,
                               @RequestBody @Valid UpdateGoalRequest request,
                               @AuthenticationPrincipal UUID userId) {
        return goalService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal UUID userId) {
        goalService.delete(id, userId);
    }

    @PatchMapping("/{id}/progress")
    public GoalResponse addProgress(@PathVariable UUID id,
                                    @RequestBody @Valid GoalProgressRequest request,
                                    @AuthenticationPrincipal UUID userId) {
        return goalService.addProgress(id, request, userId);
    }
}
