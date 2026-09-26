package com.jijing.fund.application.portfolio;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 按逗号分隔文本读取导入表。能处理带引号的逗号和成对引号，不能处理引号内部的换行。
 * 扩展名为 .xlsx 但内容仍是文本时也可能被认领；真正的压缩包表格会被拒绝。
 */
public final class CsvSpreadsheetTableReader implements SpreadsheetTableReader {
    /**
     * 扩展名以 .csv 结尾，或者内容非空且首字节不是 P 时返回 true。文件名为空且内容为空、或内容像 PK 压缩包时返回 false。
     * 因此非 csv 扩展名的普通文本也会被认领，调用方若需要限制扩展名必须自己先检查。
     */
    @Override
    public boolean supports(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".csv") || (content != null && content.length > 0 && content[0] != 'P');
    }

    /**
     * 把第一行当表头，其余非空行当数据。内容为空或缺失时抛出组合异常。只有表头、只有换行或只有 BOM 时返回空列表，不视为错误。
     * 空行会被丢掉，后续行的序号因此可能和源文件行号不一致。列数少于表头时缺列为空串，多出的单元格被忽略。
     */
    @Override
    public List<LinkedHashMap<String, String>> read(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new PortfolioException("import file is empty");
        }
        String text = decode(content);
        List<String> lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> headers = parseLine(lines.getFirst());
        var rows = new ArrayList<LinkedHashMap<String, String>>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> cells = parseLine(lines.get(i));
            var row = new LinkedHashMap<String, String>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(headers.get(column).trim(), column < cells.size() ? cells.get(column).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 去掉 UTF-8 BOM 后解码。含较多空字节时改用 GB18030，否则用 UTF-8。内容为空时抛出空指针异常。
     * 非法 UTF-8 不会在这里被识别，常见中文多字节编码仍可能按 UTF-8 解码成乱码。
     */
    private static String decode(byte[] content) {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (content.length >= 3 && content[0] == (byte) 0xEF && content[1] == (byte) 0xBB && content[2] == (byte) 0xBF) {
            offset = 3;
        } else if (looksLikeGb(content)) {
            charset = Charset.forName("GB18030");
        }
        return new String(content, offset, content.length - offset, charset);
    }

    /**
     * 统计空字节占比是否超过二十分之一。UTF-8 解码本身不会因非法字节抛异常，因此捕获分支实际上只可能吃掉空内容造成的空指针异常并返回 true。
     * 没有空字节的 GB18030 中文会被判成 UTF-8。
     */
    private static boolean looksLikeGb(byte[] content) {
        try {
            new String(content, StandardCharsets.UTF_8);
            int bad = 0;
            for (byte value : content) {
                if (value == 0) {
                    bad++;
                }
            }
            return bad > content.length / 20;
        } catch (Exception exception) {
            return true;
        }
    }

    /**
     * 按逗号切分一行，引号内的逗号保留，连续两个引号变成一个引号。不检查引号是否成对，缺右引号时其余内容都算进当前单元格。
     * 行文本为空时抛出空指针异常。
     */
    private static List<String> parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
