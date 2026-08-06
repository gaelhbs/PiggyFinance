package com.piggy.piggyfinance.service;

import java.time.LocalDate;
import java.util.UUID;

public interface ReportService {
    byte[] generatePdf(UUID userId, LocalDate startDate, LocalDate endDate);
}
