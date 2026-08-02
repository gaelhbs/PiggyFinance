package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;

import java.util.List;
import java.util.UUID;

public interface GoalService {
    GoalResponse create(CreateGoalRequest request, UUID userId);
    List<GoalResponse> list(UUID userId);
    List<GoalResponse> listByPhone(String phoneNumber);
    GoalResponse update(UUID goalId, UpdateGoalRequest request, UUID userId);
    void delete(UUID goalId, UUID userId);
    GoalResponse addProgress(UUID goalId, GoalProgressRequest request, UUID userId);
}
