package com.jijing.fund.knowledge.port;
import java.net.URI;
public interface AuthorizedDocumentProvider {
    FetchedDocument fetch(URI uri);
    record FetchedDocument(URI sourceUri,String fileName,String contentType,byte[]content){public FetchedDocument{content=content.clone();}public byte[]content(){return content.clone();}}
}
