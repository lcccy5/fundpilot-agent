package com.jijing.fund.application.watchlist;
import java.util.List;
public record MergeLocalResult(String groupId,int added,int existing,int rejected,List<String> rejectedCodes){}
