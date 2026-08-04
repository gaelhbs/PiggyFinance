package com.piggy.piggyfinance.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.piggy.piggyfinance.model.dto.ReportData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ReportPdfGenerator {

    private final TemplateEngine templateEngine;

    public byte[] render(String templateName, ReportData reportData) {
        Context context = buildContext(reportData);
        String html = templateEngine.process(templateName, context);
        return convertToPdf(html);
    }

    private Context buildContext(ReportData data) {
        Context context = new Context();
        context.setVariable("startDate", data.startDate());
        context.setVariable("endDate", data.endDate());
        context.setVariable("totalIncome", data.totalIncome());
        context.setVariable("totalExpense", data.totalExpense());
        context.setVariable("balance", data.balance());
        context.setVariable("categoryBreakdown", data.categoryBreakdown());
        context.setVariable("transactions", data.transactions());
        context.setVariable("transactionsTruncated", data.transactionsTruncated());
        context.setVariable("totalTransactionCount", data.totalTransactionCount());
        context.setVariable("goals", data.goals());
        return context;
    }

    private byte[] convertToPdf(String html) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
        return outputStream.toByteArray();
    }
}
