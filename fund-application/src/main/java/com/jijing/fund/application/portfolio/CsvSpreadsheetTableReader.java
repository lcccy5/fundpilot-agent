package com.jijing.fund.application.portfolio;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CsvSpreadsheetTableReader implements SpreadsheetTableReader {
    @Override public boolean supports(String fileName,byte[] content){
        String name=fileName==null?"":fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".csv")||(content!=null&&content.length>0&&content[0]!='P');
    }
    @Override public List<LinkedHashMap<String,String>> read(String fileName,byte[] content){
        if(content==null||content.length==0)throw new PortfolioException("import file is empty");
        String text=decode(content);
        List<String> lines=text.replace("\r\n","\n").replace('\r','\n').lines().toList();
        if(lines.isEmpty())return List.of();
        List<String> headers=parseLine(lines.getFirst());
        var rows=new ArrayList<LinkedHashMap<String,String>>();
        for(int i=1;i<lines.size();i++){
            if(lines.get(i).isBlank())continue;
            List<String> cells=parseLine(lines.get(i));
            var row=new LinkedHashMap<String,String>();
            for(int c=0;c<headers.size();c++)row.put(headers.get(c).trim(),c<cells.size()?cells.get(c).trim():"");
            rows.add(row);
        }
        return rows;
    }
    private static String decode(byte[] content){
        int offset=0;Charset charset=StandardCharsets.UTF_8;
        if(content.length>=3&&content[0]==(byte)0xEF&&content[1]==(byte)0xBB&&content[2]==(byte)0xBF)offset=3;
        else if(looksLikeGb(content))charset=Charset.forName("GB18030");
        return new String(content,offset,content.length-offset,charset);
    }
    private static boolean looksLikeGb(byte[] content){
        try{new String(content,StandardCharsets.UTF_8);int bad=0;for(byte b:content)if(b==0)bad++;return bad>content.length/20;}catch(Exception e){return true;}
    }
    private static List<String> parseLine(String line){
        List<String> cells=new ArrayList<>();StringBuilder current=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){
            char ch=line.charAt(i);
            if(ch=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){current.append('"');i++;}else quoted=!quoted;}
            else if(ch==','&&!quoted){cells.add(current.toString());current.setLength(0);}
            else current.append(ch);
        }
        cells.add(current.toString());return cells;
    }
}
