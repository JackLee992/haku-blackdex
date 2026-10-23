package top.niunaijun.blackdex.data;

import android.content.pm.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Reads only PackageManager-selected base/split APKs for one explicitly selected application. */
public final class PackageSnapshot {
    public interface Observer { void copying(String name,long copied,long total); }
    public final File directory;
    public final JSONObject report;
    private PackageSnapshot(File directory,JSONObject report){this.directory=directory;this.report=report;}
    public long bytes(){return report.optLong("packageBytes");}
    public static PackageSnapshot create(File directory,String packageName,PackageManager pm,VerifiedCopies.Cancellation cancel,Observer observer) throws Exception {
        PackageInfo info=pm.getPackageInfo(packageName,0);ApplicationInfo app=info.applicationInfo;
        List<File> sources=new ArrayList<>();List<String> names=new ArrayList<>();List<String> splitNames=new ArrayList<>();
        sources.add(new File(app.sourceDir));names.add("base.apk");splitNames.add("");
        if(app.splitSourceDirs!=null)for(int i=0;i<app.splitSourceDirs.length;i++){
            File source=new File(app.splitSourceDirs[i]);String name=source.getName();
            if(!name.endsWith(".apk")||!ReceiptArchive.safeName(name)||name.contains("/")||names.contains(name))throw new IOException("Duplicate or unsafe original APK basename: "+name);
            sources.add(source);names.add(name);splitNames.add(info.splitNames!=null&&i<info.splitNames.length?info.splitNames[i]:name);
        }
        long total=0;for(File source:sources){if(!source.isFile())throw new IOException("Installed APK is unreadable: "+source.getName());total=VerifiedCopies.add(total,source.length());}
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not create package snapshot staging directory");
        VerifiedCopies.requireCapacity(total,directory.getUsableSpace());
        JSONArray entries=new JSONArray();long complete=0;
        for(int i=0;i<sources.size();i++){
            File source=sources.get(i);String logical="original/"+names.get(i);final long previous=complete,all=total;
            ReceiptArchive.Entry entry=VerifiedCopies.copy(source,new File(directory,logical),logical,cancel,bytes->{if(observer!=null)observer.copying(logical,previous+bytes,all);});
            JSONObject item=new JSONObject();item.put("name",entry.name);item.put("origin","package_copy");item.put("sourceRole",i==0?"base":"split");item.put("originalBaseName",source.getName());
            if(i>0)item.put("splitName",splitNames.get(i));item.put("bytes",entry.bytes);item.put("sha256",entry.sha256);entries.put(item);complete=VerifiedCopies.add(complete,entry.bytes);
        }
        PackageInfo after=pm.getPackageInfo(packageName,0);
        if(info.lastUpdateTime!=after.lastUpdateTime||info.getLongVersionCode()!=after.getLongVersionCode())throw new IOException("Installed package changed during snapshot; retry capture");
        if(cancel!=null&&cancel.requested())throw new InterruptedIOException("Capture cancelled by user");
        JSONObject report=new JSONObject();report.put("schemaVersion",1);report.put("packageName",packageName);report.put("versionName",info.versionName);report.put("versionCode",info.getLongVersionCode());
        report.put("origin","package_copy");report.put("bytePreserved",true);report.put("packageBytes",complete);report.put("artifacts",entries);report.put("capturedAt",System.currentTimeMillis());
        report.put("description","Unmodified installed base/split APK bytes. These copies are separate from captured runtime DEX; no signing, rebuilding or payload replacement was performed.");
        ExportStore.write(new File(directory,"package-copy-report.json"),report.toString(2));return new PackageSnapshot(directory,report);
    }
    public static PackageSnapshot load(File directory,String packageName) throws Exception {
        JSONObject report=ExportStore.read(new File(directory,"package-copy-report.json"));
        if(!packageName.equals(report.getString("packageName"))||!report.getBoolean("bytePreserved"))throw new IOException("Package snapshot identity mismatch");
        JSONArray artifacts=report.getJSONArray("artifacts");
        if(artifacts.length()==0)throw new IOException("Package snapshot has no base APK");
        Set<String> seen=new HashSet<>();long total=0;
        for(int i=0;i<artifacts.length();i++){JSONObject entry=artifacts.getJSONObject(i);String name=entry.getString("name");
            if(!ReceiptArchive.safeName(name)||!name.startsWith("original/")||!seen.add(name))throw new IOException("Unsafe package snapshot entry");
            File file=new File(directory,name);
            if(!file.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator)||file.length()!=entry.getLong("bytes")||!ReceiptArchive.digest(file).equals(entry.getString("sha256")))throw new IOException("Package snapshot readback mismatch");
            total=VerifiedCopies.add(total,file.length());
        }
        if(!seen.contains("original/base.apk")||total!=report.getLong("packageBytes"))throw new IOException("Package snapshot inventory mismatch");
        return new PackageSnapshot(directory,report);
    }
}
