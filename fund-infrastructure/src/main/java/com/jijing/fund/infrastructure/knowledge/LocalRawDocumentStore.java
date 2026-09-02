package com.jijing.fund.infrastructure.knowledge;

import com.jijing.fund.knowledge.port.RawDocumentStore;
import java.nio.file.*;
import java.util.Locale;

public final class LocalRawDocumentStore implements RawDocumentStore {
    private final Path root;
    public LocalRawDocumentStore(Path root){try{this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);}catch(Exception ex){throw new IllegalStateException("Cannot initialize document storage",ex);}}
    @Override public String store(String hash,String fileName,byte[]content){try{String extension=extension(fileName);Path target=root.resolve(hash.substring(0,2)).resolve(hash+extension).normalize();ensureInside(target);Files.createDirectories(target.getParent());if(!Files.exists(target))try{Files.write(target,content,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}return root.relativize(target).toString().replace('\\','/');}catch(Exception ex){throw new IllegalStateException("Cannot store raw document",ex);}}
    @Override public byte[] read(String storageKey){try{Path target=root.resolve(storageKey).normalize();ensureInside(target);return Files.readAllBytes(target);}catch(Exception ex){throw new IllegalStateException("Cannot read raw document",ex);}}
    private void ensureInside(Path target){if(!target.startsWith(root))throw new SecurityException("Document path escapes configured storage root");}
    private String extension(String fileName){if(fileName==null)return ".bin";String lower=fileName.toLowerCase(Locale.ROOT);if(lower.endsWith(".pdf"))return ".pdf";if(lower.endsWith(".html")||lower.endsWith(".htm"))return ".html";if(lower.endsWith(".txt"))return ".txt";return ".bin";}
}
