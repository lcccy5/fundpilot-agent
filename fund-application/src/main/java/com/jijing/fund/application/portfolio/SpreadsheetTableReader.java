package com.jijing.fund.application.portfolio;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 把上传的表格读成按列名索引的行。实现应先用 {@link #supports} 声明自己能处理的文件，再由调用方选择。
 */
public interface SpreadsheetTableReader {
    /**
     * 判断能否读取该文件。文件名或内容为空时应返回 false，而不是抛异常；返回 true 只表示愿意尝试，不保证内容合法。
     */
    boolean supports(String fileName, byte[] content);

    /**
     * 读取表头之后的数据行，并保持列的出现顺序。空内容应失败；无法识别的内容由实现决定是抛异常还是返回空表。
     */
    List<LinkedHashMap<String, String>> read(String fileName, byte[] content);
}
