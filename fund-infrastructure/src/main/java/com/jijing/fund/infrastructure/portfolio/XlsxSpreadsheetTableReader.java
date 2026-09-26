package com.jijing.fund.infrastructure.portfolio;

import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.SpreadsheetTableReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 读取 xlsx 第一张表。文件名为 {@code .xlsx} 或内容以 PK 开头即认为支持。
 * 内容为 null 或空数组时抛出“导入文件为空”。没有表或没有表头时返回空列表，不算解析失败。
 * 公式单元格读成空字符串，不计算公式。坏文件抛出“无法解析”。
 * 最多 2000 行数据。没有网络超时、连接失败或重复提交。
 * 解压比下限被设成 0.01，这是进程级静态值。
 */
public final class XlsxSpreadsheetTableReader implements SpreadsheetTableReader {
    /** 扩展名或 ZIP 魔数二者满足其一。内容长度不足 2 时只看文件名。 */
    @Override
    public boolean supports(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || (content != null && content.length >= 2 && content[0] == 'P' && content[1] == 'K');
    }

    /**
     * 第一行是表头。数据行全空白则跳过。日期单元格格式化成 ISO 本地日期，数值用 {@code double} 的字符串。
     */
    @Override
    public List<LinkedHashMap<String, String>> read(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new PortfolioException("import file is empty");
        }
        ZipSecureFile.setMinInflateRatio(0.01);
        try (ByteArrayInputStream in = new ByteArrayInputStream(content); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.setIgnoreMissingWorkbooks(true);
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                return List.of();
            }
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return List.of();
            }
            List<String> headers = new ArrayList<>();
            for (int column = 0; column < headerRow.getLastCellNum(); column++) {
                headers.add(text(headerRow.getCell(column), evaluator).trim());
            }
            List<LinkedHashMap<String, String>> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum() && rows.size() < 2000; index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                LinkedHashMap<String, String> mapped = new LinkedHashMap<>();
                boolean empty = true;
                for (int column = 0; column < headers.size(); column++) {
                    String value = text(row.getCell(column), evaluator).trim();
                    if (!value.isBlank()) {
                        empty = false;
                    }
                    mapped.put(headers.get(column), value);
                }
                if (!empty) {
                    rows.add(mapped);
                }
            }
            return rows;
        } catch (PortfolioException e) {
            throw e;
        } catch (Exception e) {
            throw new PortfolioException("xlsx cannot be parsed");
        }
    }

    /**
     * 公式不求值。布尔和数字转成字符串；其他类型以及空单元格返回空字符串。
     * 求值器参数目前未使用，保留是为了和创建它的调用点一致。
     */
    private static String text(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
