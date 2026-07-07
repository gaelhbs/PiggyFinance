package com.piggy.piggyfinance.service;

import com.piggy.piggyfinance.exceptions.BusinessException;
import com.piggy.piggyfinance.exceptions.UnauthorizedException;
import com.piggy.piggyfinance.model.Goal;
import com.piggy.piggyfinance.model.User;
import com.piggy.piggyfinance.model.requests.CreateGoalRequest;
import com.piggy.piggyfinance.model.requests.GoalProgressRequest;
import com.piggy.piggyfinance.model.requests.UpdateGoalRequest;
import com.piggy.piggyfinance.model.responses.GoalResponse;
import com.piggy.piggyfinance.repository.GoalRepository;
import com.piggy.piggyfinance.repository.UserRepository;
import com.piggy.piggyfinance.service.impl.GoalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalServiceImplTest {

    @Mock GoalRepository goalRepository;
    @Mock UserRepository userRepository;
    @InjectMocks GoalServiceImpl service;

    private UUID userId;
    private UUID goalId;
    private User user;
    private Goal goal;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        goalId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .name("Test")
                .email("test@test.com")
                .password("x")
                .createdAt(LocalDateTime.now())
                .build();
        goal = Goal.builder()
                .id(goalId).user(user).name("Casa própria")
                .targetAmount(new BigDecimal("50000"))
                .currentAmount(new BigDecimal("10000"))
                .iconName("Home")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void create_savesGoalWithInitialAmount() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.save(any())).thenReturn(goal);

        CreateGoalRequest req = new CreateGoalRequest("Casa própria", new BigDecimal("50000"), new BigDecimal("10000"), "Home");
        GoalResponse response = service.create(req, userId);

        assertThat(response.name()).isEqualTo("Casa própria");
        verify(goalRepository).save(any(Goal.class));
    }

    @Test
    void create_clampsInitialAmountToTarget() {
        BigDecimal overAmount = new BigDecimal("99999");
        Goal clamped = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.save(any())).thenReturn(clamped);

        CreateGoalRequest req = new CreateGoalRequest("Casa própria", new BigDecimal("50000"), overAmount, "Home");
        service.create(req, userId);

        verify(goalRepository).save(argThat(g -> g.getCurrentAmount().compareTo(new BigDecimal("50000")) == 0));
    }

    @Test
    void list_returnsUserGoals() {
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(goal));
        List<GoalResponse> result = service.list(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Casa própria");
    }

    @Test
    void addProgress_throwsWhenAmountExceedsRemaining() {
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

        // goal: currentAmount=10000, targetAmount=50000, remaining=40000
        GoalProgressRequest req = new GoalProgressRequest(new BigDecimal("40001"));
        assertThatThrownBy(() -> service.addProgress(goalId, req, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("excede o restante");
    }

    @Test
    void addProgress_exactRemainingCompletes() {
        Goal completed = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenReturn(completed);

        GoalProgressRequest req = new GoalProgressRequest(new BigDecimal("40000"));
        GoalResponse response = service.addProgress(goalId, req, userId);

        assertThat(response.currentAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void delete_throwsWhenGoalNotOwned() {
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(goalId, userId))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void update_updatesNameAndTarget() {
        Goal updated = goal.toBuilder().name("Novo nome").targetAmount(new BigDecimal("60000")).build();
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenReturn(updated);

        UpdateGoalRequest req = new UpdateGoalRequest("Novo nome", new BigDecimal("60000"), "Home");
        GoalResponse response = service.update(goalId, req, userId);

        assertThat(response.name()).isEqualTo("Novo nome");
    }
}
