package top.niunaijun.blackdex;

import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import top.niunaijun.blackbox.core.HakuCaptureSession;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Exercises the real Android AtomicFile .new publication behavior, without production hooks. */
@RunWith(AndroidJUnit4.class)
public final class HakuSessionReadConcurrencyTest {
    private static final long WAIT_SECONDS=5;

    @Test public void reportReaderWaitsWithoutDeletingPendingPublication() throws Throwable {
        assumeTrue("Qualified Android 13 regression",Build.VERSION.SDK_INT==33);
        String packageName=ownPackage("report");
        File session=HakuCaptureSession.begin(packageName).getCanonicalFile();
        File ownDirectory=session.getParentFile().getCanonicalFile();
        AtomicBoolean drained=new AtomicBoolean(true);
        try {
            // Read before the writer acquires either lock: no recursive FileLock acquisition.
            JSONObject initial=HakuCaptureSession.read(session);
            assertFalse(initial.getBoolean("activityOnCreate"));
            JSONObject updated=new JSONObject(initial.toString()).put("activityOnCreate",true);
            JSONObject result=readDuringPublication(
                    new File(session,HakuCaptureSession.REPORT),new File(session,".session-lock"),
                    updated.toString(2).getBytes(StandardCharsets.UTF_8),
                    ()->HakuCaptureSession.read(session),drained);
            assertEquals(session.getName(),result.getString("sessionId"));
            assertTrue("Reader must observe the committed report",result.getBoolean("activityOnCreate"));
            assertTrue(HakuCaptureSession.read(session).getBoolean("activityOnCreate"));
        } finally {
            if(drained.get())removeOwnedDirectory(ownDirectory,packageName);
        }
    }

    @Test public void activePointerReaderWaitsWithoutDeletingPendingPublication() throws Throwable {
        assumeTrue("Qualified Android 13 regression",Build.VERSION.SDK_INT==33);
        String packageName=ownPackage("pointer");
        File first=HakuCaptureSession.begin(packageName).getCanonicalFile();
        File ownDirectory=first.getParentFile().getCanonicalFile();
        AtomicBoolean drained=new AtomicBoolean(true);
        try {
            File second=HakuCaptureSession.begin(packageName);
            assertEquals(second.getCanonicalFile(),HakuCaptureSession.current(packageName).getCanonicalFile());
            File result=readDuringPublication(
                    new File(ownDirectory,"active-session"),new File(ownDirectory,".active-session-lock"),
                    first.getName().getBytes(StandardCharsets.UTF_8),
                    ()->HakuCaptureSession.current(packageName),drained);
            assertEquals("Reader must observe the committed active pointer",first.getCanonicalFile(),result.getCanonicalFile());
            assertEquals(first.getCanonicalFile(),HakuCaptureSession.current(packageName).getCanonicalFile());
        } finally {
            if(drained.get())removeOwnedDirectory(ownDirectory,packageName);
        }
    }

    private static <T> T readDuringPublication(File base,File lockFile,byte[] replacement,
                                                Callable<T> read,AtomicBoolean drained) throws Throwable {
        final byte[] initial=rawRead(base);
        final File pending=new File(base.getPath()+".new");
        final CountDownLatch pendingWrite=new CountDownLatch(1),releaseWriter=new CountDownLatch(1),readerEntering=new CountDownLatch(1);
        FutureTask<Void> writerTask=new FutureTask<>(()->{
            synchronized(HakuCaptureSession.class) {
                try(RandomAccessFile handle=new RandomAccessFile(lockFile,"rw");FileLock ignored=handle.getChannel().lock()) {
                    AtomicFile atomic=new AtomicFile(base);FileOutputStream output=null;
                    try {
                        output=atomic.startWrite();output.write(replacement);output.flush();output.getFD().sync();
                        pendingWrite.countDown();
                        if(!releaseWriter.await(WAIT_SECONDS,TimeUnit.SECONDS))throw new TimeoutException("Test did not release pending writer");
                        atomic.finishWrite(output);output=null;
                    } finally {if(output!=null)atomic.failWrite(output);}
                }
            }
            return null;
        });
        FutureTask<T> readerTask=new FutureTask<>(()->{readerEntering.countDown();return read.call();});
        Thread writer=new Thread(writerTask,"haku-atomic-regression-writer"),reader=new Thread(readerTask,"haku-atomic-regression-reader");
        boolean readerStarted=false;Throwable primary=null;
        drained.set(false);writer.start();
        try {
            assertTrue("Writer must reach a flushed pending AtomicFile publication",pendingWrite.await(WAIT_SECONDS,TimeUnit.SECONDS));
            assertTrue("Real Android AtomicFile must create .new",pending.isFile());
            reader.start();readerStarted=true;
            assertTrue("Reader must enter its public production read",readerEntering.await(WAIT_SECONDS,TimeUnit.SECONDS));
            boolean blocked=observeBlocked(reader,readerTask);
            // Raw FileInputStream deliberately avoids AtomicFile.openRead here.
            assertTrue("Reader deleted the writer's pending .new publication",pending.isFile());
            assertArrayEquals("Base report/pointer changed before publication",initial,rawRead(base));
            assertTrue("Public reader must wait while the writer holds its publication locks",blocked);
            assertFalse("Reader returned before the pending writer was released",readerTask.isDone());
            releaseWriter.countDown();
            writerTask.get(WAIT_SECONDS,TimeUnit.SECONDS);
            T result=readerTask.get(WAIT_SECONDS,TimeUnit.SECONDS);
            assertArrayEquals("Writer must publish the exact replacement bytes",replacement,rawRead(base));
            assertFalse("Successful publication must not leave .new",pending.exists());
            return result;
        } catch(Throwable failure) {primary=failure;throw failure;}
        finally {
            // Release first, even when an assertion fails: never leave a test reader holding up the host.
            releaseWriter.countDown();
            try {
                writerTask.get(WAIT_SECONDS,TimeUnit.SECONDS);
                if(readerStarted)readerTask.get(WAIT_SECONDS,TimeUnit.SECONDS);
                writer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                if(readerStarted)reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                assertFalse("Writer did not drain",writer.isAlive());
                assertFalse("Reader did not drain",readerStarted&&reader.isAlive());
            } catch(Throwable cleanupFailure) {
                if(primary!=null)primary.addSuppressed(cleanupFailure);else throw cleanupFailure;
            } finally {
                drained.set(!writer.isAlive()&&(!readerStarted||!reader.isAlive()));
            }
        }
    }

    private static boolean observeBlocked(Thread reader,FutureTask<?> result) {
        long deadline=SystemClock.elapsedRealtimeNanos()+TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while(SystemClock.elapsedRealtimeNanos()<deadline) {
            Thread.State state=reader.getState();
            if(state==Thread.State.BLOCKED||state==Thread.State.WAITING)return !result.isDone();
            if(result.isDone())return false;
            Thread.yield();
        }
        return false;
    }
    private static String ownPackage(String role){return "com.jacklee.haku.regression."+role+".t"+UUID.randomUUID().toString().replace("-","");}
    private static byte[] rawRead(File file) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
        try(InputStream input=new FileInputStream(file)){int count;while((count=input.read(buffer))!=-1)out.write(buffer,0,count);}
        return out.toByteArray();
    }
    private static void removeOwnedDirectory(File directory,String packageName) throws IOException {
        if(directory==null||!directory.getName().equals(packageName)||!packageName.startsWith("com.jacklee.haku.regression."))
            throw new IOException("Refusing cleanup outside this test's unique package");
        removeTree(directory);
    }
    private static void removeTree(File file) throws IOException {
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("Linked test cleanup entry rejected");
        File[] children=file.listFiles();if(children!=null)for(File child:children)removeTree(child);
        if(file.exists()&&!file.delete())throw new IOException("Could not remove test-owned entry: "+file.getName());
    }
}
