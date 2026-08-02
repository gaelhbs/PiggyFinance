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
    @Mock com.piggy.piggyfinance.service.EntitlementService entitlementService;
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
    void create_throwsWhenInitialAmountExceedsTarget() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("50001"), "Home");
        assertThatThrownBy(() -> service.create(req, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("não pode ser maior que o valor alvo");
    }

    @Test
    void create_throwsWhenInitialAmountIsNegative() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("-1"), "Home");
        assertThatThrownBy(() -> service.create(req, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("não pode ser negativo");
    }

    @Test
    void create_allowsInitialAmountEqualToTarget() {
        Goal full = goal.toBuilder().currentAmount(new BigDecimal("50000")).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(goalRepository.save(any())).thenReturn(full);

        CreateGoalRequest req = new CreateGoalRequest("Casa", new BigDecimal("50000"), new BigDecimal("50000"), "Home");
        GoalResponse response = service.create(req, userId);

        assertThat(response.currentAmount()).isEqualByComparingTo("50000");
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

    @Test
    void update_throwsWhenNewTargetBelowCurrentAmount() {
        // goal: currentAmount=10000
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

        UpdateGoalRequest req = new UpdateGoalRequest("Casa", new BigDecimal("9999"), "Home");
        assertThatThrownBy(() -> service.update(goalId, req, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("não pode ser menor que o já investido");
    }

    @Test
    void update_allowsTargetEqualToCurrentAmount() {
        // currentAmount=10000, new target=10000 → valid (goal becomes complete)
        Goal updated = goal.toBuilder().targetAmount(new BigDecimal("10000")).build();
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenReturn(updated);

        UpdateGoalRequest req = new UpdateGoalRequest("Casa", new BigDecimal("10000"), "Home");
        GoalResponse response = service.update(goalId, req, userId);

        assertThat(response.targetAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void create_freeUserWithExistingGoal_throwsFeatureLocked() {
        when(entitlementService.getEffectiveTier(userId))
                .thenReturn(com.piggy.piggyfinance.enums.SubscriptionTier.FREE);
        when(goalRepository.countByUserId(userId)).thenReturn(1L);

        var req = new com.piggy.piggyfinance.model.requests.CreateGoalRequest(
                "Viagem", new java.math.BigDecimal("1000"), null, "plane");

        assertThatThrownBy(() -> service.create(req, userId))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class)
                .satisfies(ex -> assertThat(((com.piggy.piggyfinance.exceptions.FeatureLockedException) ex).getRequiredTier())
                        .isEqualTo(com.piggy.piggyfinance.enums.SubscriptionTier.ESSENCIAL));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void create_paidUser_skipsGoalCap() {
        when(entitlementService.getEffectiveTier(userId))
                .thenReturn(com.piggy.piggyfinance.enums.SubscriptionTier.PRO);
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        com.piggy.piggyfinance.model.Goal g = mock(com.piggy.piggyfinance.model.Goal.class);
        when(goalRepository.save(any())).thenReturn(g);

        var req = new com.piggy.piggyfinance.model.requests.CreateGoalRequest(
                "Viagem", new java.math.BigDecimal("1000"), null, "plane");

        service.create(req, userId);
        verify(goalRepository).save(any());
        verify(goalRepository, never()).countByUserId(any());
    }

    @Test
    void listByPhone_success_delegatesToList() {
        var phone = "+5575900000012";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("wg@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        when(goalRepository.findByUserIdOrderByCreatedAtAsc(u.getId())).thenReturn(List.of(goal));

        List<GoalResponse> result = service.listByPhone(phone);

        assertThat(result).hasSize(1);
    }

    @Test
    void listByPhone_phoneNotLinked_throws() {
        var phone = "+5575900000013";
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByPhone(phone))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.PhoneNotLinkedException.class);
    }

    @Test
    void listByPhone_nonPro_throwsFeatureLocked() {
        var phone = "+5575900000014";
        User u = User.builder().id(UUID.randomUUID()).name("W").email("wg2@test.com")
                .password("h").createdAt(LocalDateTime.now()).phoneNumber(phone).build();
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.of(u));
        org.mockito.Mockito.doThrow(new com.piggy.piggyfinance.exceptions.FeatureLockedException(
                        "This feature requires the PRO plan", com.piggy.piggyfinance.enums.SubscriptionTier.PRO))
                .when(entitlementService).requireTier(u.getId(), com.piggy.piggyfinance.enums.SubscriptionTier.PRO);

        assertThatThrownBy(() -> service.listByPhone(phone))
                .isInstanceOf(com.piggy.piggyfinance.exceptions.FeatureLockedException.class);
    }
}
