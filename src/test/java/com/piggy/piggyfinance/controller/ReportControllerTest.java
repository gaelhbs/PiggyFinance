package com.piggy.piggyfinance.controller;

import com.piggy.piggyfinance.enums.SubscriptionTier;
import com.piggy.piggyfinance.exceptions.FeatureLockedException;
import com.piggy.piggyfinance.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock ReportService reportService;
    @InjectMocks ReportController controller;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void generatePdf_returnsOkWithPdfContentTypeAndDisposition() {
        byte[] pdfBytes = {1, 2, 3};
        when(reportService.generatePdf(eq(userId), any(), any())).thenReturn(pdfBytes);

        ResponseEntity<byte[]> response = controller.generatePdf(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(response.getBody()).isEqualTo(pdfBytes);
    }

    @Test
    void generatePdf_serviceThrowsFeatureLocked_propagatesException() {
        when(reportService.generatePdf(eq(userId), any(), any()))
                .thenThrow(new FeatureLockedException("This feature requires the ESSENCIAL plan", SubscriptionTier.ESSENCIAL));

        assertThatThrownBy(() -> controller.generatePdf(null, null, userId))
                .isInstanceOf(FeatureLockedException.class);
    }
}
