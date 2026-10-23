package top.niunaijun.blackdex.data;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class ReceiptArchiveTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File session() throws Exception {
        File session=temporary.newFolder();
        Files.write(new File(session,"capture-report.json").toPath(),"{\"status\":\"verified\"}".getBytes("UTF-8"));
        Files.write(new File(session,"runtime.dex").toPath(),new byte[]{0,1,2,3,4});return session;
    }
    @Test public void archiveReadbackPreservesEveryByte() throws Exception {
        File session=session(),zip=new File(temporary.newFolder(),"export.zip");List<ReceiptArchive.Entry> entries=ReceiptArchive.inventory(session);
        ReceiptArchive.create(session,zip,entries);ReceiptArchive.verify(zip,entries);
        assertEquals(2,entries.size());assertTrue(zip.length()>0);
    }
    @Test public void sourceMutationAfterInventoryCannotPublish() throws Exception {
        File session=session(),zip=new File(temporary.newFolder(),"export.zip");List<ReceiptArchive.Entry> entries=ReceiptArchive.inventory(session);
        Files.write(new File(session,"runtime.dex").toPath(),new byte[]{4,3,2,1,0});
        try{ReceiptArchive.create(session,zip,entries);fail("Changed bytes must not publish");}catch(IOException expected){assertTrue(expected.getMessage().contains("mismatch"));}
        assertFalse(zip.exists());assertFalse(new File(zip.getPath()+".partial").exists());
    }
    @Test public void extraArchiveFileIsRejectedEvenWhenExpectedFilesMatch() throws Exception {
        File session=session(),zip=new File(temporary.newFolder(),"injected.zip");List<ReceiptArchive.Entry> entries=ReceiptArchive.inventory(session);
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zip))){
            for(ReceiptArchive.Entry entry:entries){out.putNextEntry(new ZipEntry(entry.name));out.write(Files.readAllBytes(new File(session,entry.name).toPath()));out.closeEntry();}
            out.putNextEntry(new ZipEntry("unexpected.dex"));out.write(1);out.closeEntry();
        }
        try{ReceiptArchive.verify(zip,entries);fail("Unexpected bytes must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("Unexpected"));}
    }
    @Test public void missingFileAndWrongReceiptSizeAreRejected() throws Exception {
        File session=session(),zip=new File(temporary.newFolder(),"export.zip");List<ReceiptArchive.Entry> entries=ReceiptArchive.inventory(session);ReceiptArchive.create(session,zip,entries);
        List<ReceiptArchive.Entry> incomplete=new ArrayList<>(entries);incomplete.add(new ReceiptArchive.Entry("missing.dex",1,"00"));
        try{ReceiptArchive.verify(zip,incomplete);fail("Missing file must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("missing"));}
        ReceiptArchive.Entry first=entries.get(0);entries.set(0,new ReceiptArchive.Entry(first.name,first.bytes+1,first.sha256));
        try{ReceiptArchive.verify(zip,entries);fail("Wrong size must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("mismatch"));}
    }
    @Test public void unpublishedOrLinkedFilesAreRejected() throws Exception {
        File session=session();File pending=new File(session,"runtime.dex.partial");Files.write(pending.toPath(),new byte[]{1});
        try{ReceiptArchive.inventory(session);fail("Pending files must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("unpublished"));}
        assertTrue(pending.delete());File outside=temporary.newFile();Files.createSymbolicLink(new File(session,"linked.dex").toPath(),outside.toPath());
        try{ReceiptArchive.inventory(session);fail("Links must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("Linked"));}
    }
    @Test public void traversalReceiptCannotBeVerified() throws Exception {
        File zip=new File(temporary.newFolder(),"export.zip");
        try(ZipOutputStream ignored=new ZipOutputStream(new FileOutputStream(zip))){}
        try{ReceiptArchive.verify(zip,Collections.singletonList(new ReceiptArchive.Entry("../escape.dex",0,"00")));fail("Traversal must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("Unsafe"));}
    }
}
