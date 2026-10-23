package top.niunaijun.blackdex.data;

import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Android-free full-write/readback copy and storage arithmetic. No APK transformation. */
public final class VerifiedCopies {
    public static final long MAX_EXPORT_BYTES=2L*1024*1024*1024;
    public static final long RESERVE_BYTES=16L*1024*1024;
    public interface Cancellation { boolean requested(); }
    public interface Progress { void copied(long bytes); }
    private VerifiedCopies() {}
    public static long add(long left,long right) throws IOException {
        if(left<0||right<0||left>Long.MAX_VALUE-right)throw new IOException("Export byte count overflow");
        return left+right;
    }
    public static void requireCapacity(long contentBytes,long freeBytes) throws IOException {
        requireArchiveCapacity(contentBytes,contentBytes,freeBytes);
    }
    public static void requireArchiveCapacity(long contentBytes,long additionalCopyBytes,long freeBytes) throws IOException {
        if(contentBytes<0||contentBytes>MAX_EXPORT_BYTES)throw new IOException("Export exceeds the 2 GiB uncompressed size limit");
        long required=add(add(contentBytes,additionalCopyBytes),add(contentBytes/16,RESERVE_BYTES));
        if(freeBytes<required)throw new IOException("Insufficient private storage: "+required+" bytes required, "+freeBytes+" bytes available");
    }
    public static ReceiptArchive.Entry copy(File source,File destination,String logicalName,Cancellation cancellation,Progress progress) throws IOException {
        check(cancellation);
        if(!ReceiptArchive.safeName(logicalName)||!source.isFile()||destination.exists())throw new IOException("Invalid package copy destination or source");
        long expected=source.length(),modified=source.lastModified();
        if(expected>MAX_EXPORT_BYTES)throw new IOException("File exceeds the 2 GiB export limit");
        File parent=destination.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not create private export staging directory");
        if(parent.getUsableSpace()<add(expected,RESERVE_BYTES))throw new IOException("Insufficient private storage while copying "+logicalName);
        File temporary=new File(destination.getPath()+".partial");
        try {
            MessageDigest copied=sha256();long total=0;byte[] buffer=new byte[65536];
            try(InputStream in=new BufferedInputStream(new FileInputStream(source));FileOutputStream out=new FileOutputStream(temporary)){
                int n;while((n=in.read(buffer))!=-1){check(cancellation);total=add(total,n);if(total>expected)throw new IOException("Source grew during copy: "+logicalName);out.write(buffer,0,n);copied.update(buffer,0,n);if(progress!=null)progress.copied(total);}
                out.getFD().sync();
            }
            check(cancellation);
            String hash=hex(copied.digest());
            if(total!=expected||source.length()!=expected||source.lastModified()!=modified||temporary.length()!=expected
                ||!hash.equals(digest(temporary,cancellation))||!hash.equals(digest(source,cancellation)))
                throw new IOException("Byte readback mismatch while copying "+logicalName);
            check(cancellation);
            if(!temporary.renameTo(destination))throw new IOException("Could not publish copied file: "+logicalName);
            return new ReceiptArchive.Entry(logicalName,total,hash);
        } finally {if(temporary.exists())temporary.delete();}
    }
    private static String digest(File file,Cancellation cancellation) throws IOException {
        MessageDigest hash=sha256();byte[] buffer=new byte[65536];
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){int n;while((n=in.read(buffer))!=-1){check(cancellation);hash.update(buffer,0,n);}}
        return hex(hash.digest());
    }
    private static void check(Cancellation cancellation) throws InterruptedIOException {
        if(cancellation!=null&&cancellation.requested())throw new InterruptedIOException("Capture cancelled by user");
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new AssertionError(impossible);}}
    private static String hex(byte[] data){StringBuilder out=new StringBuilder();for(byte value:data)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
}
