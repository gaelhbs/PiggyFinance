package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.exceptions.UserNotFoundException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.GoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoalServiceImpl implements GoalService {

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public GoalResponse create(CreateGoalRequest request, UUID userId) {
        User user = findUser(userId);
        BigDecimal initial = request.currentAmount() != null ? request.currentAmount() : BigDecimal.ZERO;
        if (initial.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("O valor inicial não pode ser negativo");
        }
        if (initial.compareTo(request.targetAmount()) > 0) {
            throw new BusinessException("O valor inicial não pode ser maior que o valor alvo");
        }
        Goal goal = goalRepository.save(Goal.builder()
                .user(user)
                .name(request.name())
                .targetAmount(request.targetAmount())
                .currentAmount(initial)
                .iconName(request.iconName())
                .build());
        return toResponse(goal);
    }

    @Override
    public List<GoalResponse> list(UUID userId) {
        return goalRepository.findByUserIdOrderByCreatedAtAsc(userId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public GoalResponse update(UUID goalId, UpdateGoalRequest request, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        if (request.targetAmount().compareTo(goal.getCurrentAmount()) < 0) {
            throw new BusinessException(
                "O valor alvo não pode ser menor que o já investido (R$ " +
                goal.getCurrentAmount().setScale(2, java.math.RoundingMode.HALF_UP)
                                       .toPlainString().replace(".", ",") + ")");
        }
        Goal updated = goalRepository.save(goal.toBuilder()
                .name(request.name())
                .targetAmount(request.targetAmount())
                .iconName(request.iconName())
                .build());
        return toResponse(updated);
    }

    @Override
    @Transactional
    public void delete(UUID goalId, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        goalRepository.delete(goal);
    }

    @Override
    @Transactional
    public GoalResponse addProgress(UUID goalId, GoalProgressRequest request, UUID userId) {
        Goal goal = findOwned(goalId, userId);
        BigDecimal remaining = goal.getTargetAmount().subtract(goal.getCurrentAmount());
        if (request.amount().compareTo(remaining) > 0) {
            throw new BusinessException(
                "O valor excede o restante da meta (R$ " +
                remaining.setScale(2, java.math.RoundingMode.HALF_UP)
                         .toPlainString().replace(".", ",") + ")");
        }
        Goal updated = goalRepository.save(goal.toBuilder()
                .currentAmount(goal.getCurrentAmount().add(request.amount()))
                .build());
        return toResponse(updated);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }

    private Goal findOwned(UUID goalId, UUID userId) {
        return goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new UnauthorizedException("Goal not found or access denied"));
    }

    private GoalResponse toResponse(Goal g) {
        return new GoalResponse(
                g.getId(), g.getName(),
                g.getTargetAmount(), g.getCurrentAmount(),
                g.getIconName(), g.getCreatedAt());
    }
}
