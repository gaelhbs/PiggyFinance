package com.piggy.piggyfinance.service.impl;

import com.piggy.piggyfinance.enums.CategoryType;
import com.piggy.piggyfinance.enums.TransactionType;
import com.piggy.piggyfinance.model.dto.CategoryBreakdownItem;
import com.piggy.piggyfinance.model.dto.GoalProgressItem;
import com.piggy.piggyfinance.model.dto.ReportData;
import com.piggy.piggyfinance.model.dto.TransactionLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfGeneratorTest {

    private ReportPdfGenerator generator;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        generator = new ReportPdfGenerator(templateEngine);
    }

    @Test
    void render_fullReportData_producesValidPdfBytes() {
        ReportData data = new ReportData(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                new BigDecimal("1000.00"),
                new BigDecimal("400.00"),
                new BigDecimal("600.00"),
                List.of(new CategoryBreakdownItem(CategoryType.FOOD, new BigDecimal("300.00"), new BigDecimal("75.00"))),
                List.of(new TransactionLineItem(LocalDate.of(2026, 8, 5), "Mercado", CategoryType.FOOD, TransactionType.EXPENSE, new BigDecimal("300.00"))),
                false,
                1,
                List.of(new GoalProgressItem("Viagem", new BigDecimal("500.00"), new BigDecimal("2000.00"), new BigDecimal("25.00")))
        );

        byte[] pdf = generator.render("reports/financial-report", data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void render_emptyReportData_producesValidPdfBytes() {
        ReportData data = new ReportData(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                false,
                0,
                List.of()
        );

        byte[] pdf = generator.render("reports/financial-report", data);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
