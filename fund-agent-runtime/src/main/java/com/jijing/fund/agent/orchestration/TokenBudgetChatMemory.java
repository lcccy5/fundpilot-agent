package com.jijing.fund.agent.orchestration;

import java.util.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;

/** Keeps recent conversation history by an approximate token budget, with a message-count safety cap. */
public final class TokenBudgetChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final int maxTokens;
    private final int maxMessages;

    
    /** 执行该 Agent 运行时组件中的 TokenBudgetChatMemory 操作。 */
    public TokenBudgetChatMemory(ChatMemoryRepository repository,int maxTokens,int maxMessages){
        this.repository=Objects.requireNonNull(repository);
        if(maxTokens<1||maxMessages<1)throw new IllegalArgumentException("memory budgets must be positive");
        this.maxTokens=maxTokens;this.maxMessages=maxMessages;
    }

    @Override 
    /** 执行该 Agent 运行时组件中的 add 操作。 */
    public void add(String conversationId,List<Message> messages){
        requireId(conversationId);Objects.requireNonNull(messages,"messages");
        if(messages.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("messages cannot contain null");
        List<Message> combined=new ArrayList<>(repository.findByConversationId(conversationId));
        for(Message message:messages){
            if(message instanceof SystemMessage)combined.removeIf(SystemMessage.class::isInstance);
            if(!combined.contains(message))combined.add(message);
        }
        repository.saveAll(conversationId,trim(combined));
    }

    @Override 
    /** 获取当前 Agent 操作所需的 get 结果。 */
    public List<Message> get(String conversationId){requireId(conversationId);return repository.findByConversationId(conversationId);}
    @Override 
    /** 执行 clear 对应的资源状态转换。 */
    public void clear(String conversationId){requireId(conversationId);repository.deleteByConversationId(conversationId);}

    List<Message> trim(List<Message> input){
        List<Message> systems=input.stream().filter(SystemMessage.class::isInstance).toList();
        List<Message> ordinary=input.stream().filter(m->!(m instanceof SystemMessage)).toList();
        ArrayDeque<Message> selected=new ArrayDeque<>();int tokens=systems.stream().mapToInt(this::tokens).sum();
        for(int i=ordinary.size()-1;i>=0&&selected.size()<maxMessages;i--){
            Message message=ordinary.get(i);int cost=tokens(message);
            if(!selected.isEmpty()&&tokens+cost>maxTokens)break;
            if(selected.isEmpty()||tokens+cost<=maxTokens){selected.addFirst(message);tokens+=cost;}
        }
        while(!selected.isEmpty()&&selected.getFirst().getMessageType()==MessageType.ASSISTANT)selected.removeFirst();
        List<Message> result=new ArrayList<>(systems);result.addAll(selected);return List.copyOf(result);
    }

    
    /** 执行该 Agent 运行时组件中的 tokens 操作。 */
    public int tokens(Message message){return estimateTokens(message==null?null:message.getText());}
    
    /** 执行该 Agent 运行时组件中的 estimateTokens 操作。 */
    public static int estimateTokens(String text){
        if(text==null||text.isBlank())return 1;
        int codePoints=text.codePointCount(0,text.length());
        return Math.max(1,(codePoints*3+3)/4);
    }
    
    /** 执行该 Agent 运行时组件中的 requireId 操作。 */
    private void requireId(String id){if(id==null||id.isBlank())throw new IllegalArgumentException("conversationId cannot be blank");}
}
