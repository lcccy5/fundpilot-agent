package com.jijing.fund.application.portfolio;

import java.util.LinkedHashMap;
import java.util.List;

public interface SpreadsheetTableReader {
    boolean supports(String fileName,byte[] content);
    List<LinkedHashMap<String,String>> read(String fileName,byte[] content);
}
