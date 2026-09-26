package com.jijing.fund.infrastructure.portfolio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.application.portfolio.PortfolioException;
import org.junit.jupiter.api.Test;

class XlsxSpreadsheetTableReaderReadabilityGapTest {
    @Test
    void emptyPayloadIsRejected() {
        XlsxSpreadsheetTableReader reader = new XlsxSpreadsheetTableReader();

        assertThatThrownBy(() -> reader.read("holdings.xlsx", new byte[0]))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file is empty");
        assertThatThrownBy(() -> reader.read("holdings.xlsx", null))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file is empty");
    }
}
