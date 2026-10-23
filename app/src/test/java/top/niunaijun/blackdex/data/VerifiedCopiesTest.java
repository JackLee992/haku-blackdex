package top.niunaijun.blackdex.data;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class VerifiedCopiesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void originalApkBytesAndArchiveNamespaceArePreserved() throws Exception {
        File apk=temporary.newFile("base.apk");byte[] content=new byte[180000];new Random(13).nextBytes(content);Files.write(apk.toPath(),content);
        File stage=temporary.newFolder(),copy=new File(stage,"original/base.apk");
        ReceiptArchive.Entry receipt=VerifiedCopies.copy(apk,copy,"original/base.apk",()->false,null);
        assertArrayEquals(content,Files.readAllBytes(copy.toPath()));assertEquals(content.length,receipt.bytes);assertEquals(ReceiptArchive.digest(apk),receipt.sha256);
        File zip=new File(temporary.newFolder(),"export.zip");ReceiptArchive.create(stage,zip,Collections.singletonList(receipt));ReceiptArchive.verify(zip,Collections.singletonList(receipt));
    }
    @Test public void cancellationDuringCopyDoesNotPublishOrLeavePartialBytes() throws Exception {
        File apk=temporary.newFile("base.apk");Files.write(apk.toPath(),new byte[200000]);File target=new File(temporary.newFolder(),"base.apk");AtomicBoolean cancelled=new AtomicBoolean();
        try {VerifiedCopies.copy(apk,target,"original/base.apk",cancelled::get,bytes->cancelled.set(true));fail("Cancelled copy must fail");}
        catch(InterruptedIOException expected){assertEquals("Capture cancelled by user",expected.getMessage());}
        assertFalse(target.exists());assertFalse(new File(target.getPath()+".partial").exists());
    }
    @Test public void cancellationBeforeCopyDoesNotOpenDestination() throws Exception {
        File apk=temporary.newFile("base.apk"),target=new File(temporary.newFolder(),"base.apk");
        try{VerifiedCopies.copy(apk,target,"original/base.apk",()->true,null);fail("Pre-cancel must fail");}catch(InterruptedIOException expected){}
        assertFalse(target.exists());assertFalse(new File(target.getPath()+".partial").exists());
    }
    @Test public void sourceGrowthDuringCopyRejectsPublication() throws Exception {
        File apk=temporary.newFile("base.apk");Files.write(apk.toPath(),new byte[200000]);File target=new File(temporary.newFolder(),"base.apk");AtomicBoolean changed=new AtomicBoolean();
        try{VerifiedCopies.copy(apk,target,"original/base.apk",()->false,bytes->{if(changed.compareAndSet(false,true))try(FileOutputStream append=new FileOutputStream(apk,true)){append.write(9);}catch(IOException failure){throw new RuntimeException(failure);}});fail("Mutated source must fail");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("grew")||expected.getMessage().contains("mismatch"));}
        assertFalse(target.exists());
    }
    @Test public void quotasAndDiskChecksUseLongArithmetic() throws Exception {
        assertEquals(2147483648L,VerifiedCopies.MAX_EXPORT_BYTES);
        VerifiedCopies.requireCapacity(VerifiedCopies.MAX_EXPORT_BYTES,8L*1024*1024*1024);
        try{VerifiedCopies.requireCapacity(VerifiedCopies.MAX_EXPORT_BYTES+1,Long.MAX_VALUE);fail("Quota must apply");}catch(IOException expected){assertTrue(expected.getMessage().contains("2 GiB"));}
        try{VerifiedCopies.requireCapacity(1000,1000);fail("Insufficient disk must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("Insufficient"));}
        try{VerifiedCopies.add(Long.MAX_VALUE,1);fail("Overflow must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("overflow"));}
        try{VerifiedCopies.requireArchiveCapacity(1000,Long.MAX_VALUE,Long.MAX_VALUE);fail("Capacity overflow must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("overflow"));}
    }
}
