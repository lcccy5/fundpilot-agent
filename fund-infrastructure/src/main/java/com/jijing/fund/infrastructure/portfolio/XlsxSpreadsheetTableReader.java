package com.jijing.fund.infrastructure.portfolio;

import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.SpreadsheetTableReader;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class XlsxSpreadsheetTableReader implements SpreadsheetTableReader {
    @Override public boolean supports(String fileName,byte[] content){
        String name=fileName==null?"":fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx")||(content!=null&&content.length>=2&&content[0]=='P'&&content[1]=='K');
    }
    @Override public List<LinkedHashMap<String,String>> read(String fileName,byte[] content){
        if(content==null||content.length==0)throw new PortfolioException("import file is empty");
        ZipSecureFile.setMinInflateRatio(0.01);
        try(var in=new ByteArrayInputStream(content);var workbook=new XSSFWorkbook(in)){
            FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();evaluator.setIgnoreMissingWorkbooks(true);
            Sheet sheet=workbook.getSheetAt(0);if(sheet==null)return List.of();
            Row headerRow=sheet.getRow(0);if(headerRow==null)return List.of();
            List<String> headers=new ArrayList<>();
            for(int c=0;c<headerRow.getLastCellNum();c++)headers.add(text(headerRow.getCell(c),evaluator).trim());
            var rows=new ArrayList<LinkedHashMap<String,String>>();
            for(int r=1;r<=sheet.getLastRowNum()&&rows.size()<2000;r++){
                Row row=sheet.getRow(r);if(row==null)continue;
                var mapped=new LinkedHashMap<String,String>();boolean empty=true;
                for(int c=0;c<headers.size();c++){String value=text(row.getCell(c),evaluator).trim();if(!value.isBlank())empty=false;mapped.put(headers.get(c),value);}
                if(!empty)rows.add(mapped);
            }
            return rows;
        }catch(PortfolioException e){throw e;}catch(Exception e){throw new PortfolioException("xlsx cannot be parsed");}
    }
    private static String text(Cell cell,FormulaEvaluator evaluator){
        if(cell==null)return "";
        if(cell.getCellType()==CellType.FORMULA)return "";
        return switch(cell.getCellType()){
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)?cell.getLocalDateTimeCellValue().toLocalDate().toString():String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
