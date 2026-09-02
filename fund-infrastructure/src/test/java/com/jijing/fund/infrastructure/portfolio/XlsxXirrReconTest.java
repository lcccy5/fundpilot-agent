package com.jijing.fund.infrastructure.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxXirrReconTest {
    @Test void xlsxCashFlowsMatchCsvExcelSample()throws Exception{
        byte[] xlsx;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet();
            var header=sheet.createRow(0);header.createCell(0).setCellValue("date");header.createCell(1).setCellValue("amount");
            var r1=sheet.createRow(1);r1.createCell(0).setCellValue("2025-01-01");r1.createCell(1).setCellValue("-100");
            var r2=sheet.createRow(2);r2.createCell(0).setCellValue("2026-01-01");r2.createCell(1).setCellValue("110");
            book.write(out);xlsx=out.toByteArray();
        }
        var rows=new XlsxSpreadsheetTableReader().read("xirr-excel-sample.xlsx",xlsx);
        var flows=new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
        for(var row:rows)flows.add(new MoneyWeightedReturnCalculator.CashFlow(LocalDate.parse(row.get("date")),new BigDecimal(row.get("amount"))));
        assertThat(new MoneyWeightedReturnCalculator().calculate(flows)).hasValueSatisfying(v->assertThat(v).isCloseTo(new BigDecimal("0.10"),within(new BigDecimal("0.001"))));
    }
}
