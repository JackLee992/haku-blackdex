package top.niunaijun.blackdex.data;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persisted export receipts. Engine claims and independent archive readback stay separate. */
public final class ExportStore {
    public static final class Receipt {
        public File directory;
        public JSONObject report;
        public String status() { return report.optString("status", "failed"); }
        public String packageName() { return report.optString("packageName", "Unknown application"); }
        public boolean hasZip() { return new File(directory, "export.zip").isFile(); }
    }
    private final File captures, exports, staging;
    public ExportStore(File files) { captures = new File(files, "haku-dex"); exports = new File(files, "haku-exports"); staging=new File(files,"haku-staging"); }
    public File session(String packageName, String path, long start) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Engine did not report a session directory");
        File directory = new File(path).getCanonicalFile();
        File parent = new File(captures, packageName).getCanonicalFile();
        if (!parent.equals(directory.getParentFile()) || !directory.isDirectory()) throw new IOException("Session directory does not match selected application");
        File report = new File(directory, "capture-report.json");
        if (report.exists() && report.lastModified() < start) throw new IOException("Stale capture report rejected");
        try {
            JSONObject identity=read(report);
            if(!packageName.equals(identity.getString("packageName")) || identity.getLong("startedAt") < start
                || !directory.getName().equals(identity.getString("sessionId")))
                throw new IOException("Session identity or freshness mismatch");
        } catch(IOException invalid) { throw invalid; }
          catch(Exception invalid) { throw new IOException("Session identity report unavailable",invalid); }
        return directory;
    }
    public File newStage() throws IOException {
        File directory=new File(staging,UUID.randomUUID().toString());
        if(!directory.mkdirs())throw new IOException("Could not create private export staging directory");
        return directory;
    }
    public File savedStage(String name) throws IOException {
        if(name==null||!name.matches("[a-f0-9-]{36}"))throw new IOException("Missing saved package snapshot");
        File directory=new File(staging,name).getCanonicalFile();
        if(!staging.getCanonicalFile().equals(directory.getParentFile()))throw new IOException("Invalid saved package snapshot");
        return directory;
    }
    public Receipt finish(String packageName,File session,File stage,boolean engineSuccess,String error,long startedAt,boolean cancelled) throws Exception {
        JSONObject engine=null,packageReport=null;String validationError=engineSuccess||error==null?"":error;
        boolean engineVerified=false,packageVerified=false;int validRuntime=0;
        long runtimeBytes=0,packageBytes=0;
        Map<String,JSONObject> origins=new HashMap<>();
        if(stage==null)stage=newStage();
        try {
            PackageSnapshot snapshot=PackageSnapshot.load(stage,packageName);
            packageReport=snapshot.report;packageVerified=true;packageBytes=snapshot.bytes();
            JSONArray copies=packageReport.getJSONArray("artifacts");
            for(int i=0;i<copies.length();i++){JSONObject copy=copies.getJSONObject(i);origins.put(copy.getString("name"),copy);}
            JSONObject metadata=new JSONObject();metadata.put("origin","package_report");origins.put("package-copy-report.json",metadata);
        } catch(Exception invalid) {
            if(engineSuccess)validationError=appendError(validationError,"Original package snapshot unavailable: "+invalid.getMessage());
            removeTree(new File(stage,"original"));new File(stage,"package-copy-report.json").delete();
        }
        removeTree(new File(stage,"runtime"));new File(stage,"capture-report.json").delete();
        if(session!=null) {
            try {
                engine=read(new File(session,"capture-report.json"));
                if(!packageName.equals(engine.getString("packageName"))||engine.getLong("startedAt")<startedAt||!session.getName().equals(engine.getString("sessionId")))
                    throw new IOException("Capture report identity or freshness mismatch");
                JSONArray artifacts=engine.getJSONArray("artifacts");long additional=new File(session,"capture-report.json").length(),total=VerifiedCopies.add(packageBytes,new File(stage,"package-copy-report.json").length());
                for(int i=0;i<artifacts.length();i++)additional=VerifiedCopies.add(additional,artifacts.getJSONObject(i).getLong("bytes"));
                total=VerifiedCopies.add(total,additional);
                VerifiedCopies.requireArchiveCapacity(total,additional,stage.getUsableSpace());
                File copiedReport=new File(stage,"capture-report.json");
                VerifiedCopies.copy(new File(session,"capture-report.json"),copiedReport,"capture-report.json",null,null);
                JSONObject reportOrigin=new JSONObject();reportOrigin.put("origin","engine_report");origins.put("capture-report.json",reportOrigin);
                for(int i=0;i<artifacts.length();i++){
                    JSONObject artifact=artifacts.getJSONObject(i);String name=artifact.getString("name");
                    if(!ReceiptArchive.safeName(name)||name.contains("/")||!name.endsWith(".dex"))throw new IOException("Unsafe reported runtime artifact");
                    File file=new File(session,name);
                    if(!file.getCanonicalPath().startsWith(session.getCanonicalPath()+File.separator)||!file.isFile()
                        ||file.length()!=artifact.getLong("bytes")||!ReceiptArchive.digest(file).equals(artifact.getString("sha256")))
                        throw new IOException("Runtime artifact readback mismatch: "+name);
                    String logical="runtime/"+name;
                    ReceiptArchive.Entry copied=VerifiedCopies.copy(file,new File(stage,logical),logical,null,null);
                    if(!copied.sha256.equals(artifact.getString("sha256")))throw new IOException("Runtime stage hash mismatch: "+name);
                    JSONObject origin=new JSONObject();origin.put("origin","runtime_dex");origin.put("engineName",name);origins.put(logical,origin);
                    runtimeBytes=VerifiedCopies.add(runtimeBytes,copied.bytes);validRuntime++;
                }
                engineVerified=engineSuccess&&"verified".equals(engine.optString("status"))&&validRuntime>0
                    &&engine.optBoolean("applicationOnCreate")&&engine.optBoolean("activityOnCreate");
                if(!engineVerified&&validationError.isEmpty())validationError=engine.optString("error","Engine did not verify application and activity capture");
            } catch(Exception invalid) {validationError=appendError(validationError,invalid.getMessage());}
        }
        File output=new File(exports,packageName+"-"+UUID.randomUUID());
        if(!output.mkdirs())throw new IOException("Could not create private export directory");
        JSONObject receipt=new JSONObject();receipt.put("schemaVersion",2);receipt.put("packageName",packageName);
        cancelled=cancelled||(engine!=null&&"Capture cancelled by user".equals(engine.optString("error")));
        receipt.put("createdAt",System.currentTimeMillis());receipt.put("startedAt",startedAt);receipt.put("cancelled",cancelled);
        receipt.put("engineVerified",engineVerified);receipt.put("packageCopiesVerified",packageVerified);
        receipt.put("runtimeDexBytes",runtimeBytes);receipt.put("packageCopyBytes",packageBytes);
        receipt.put("coverage",(engine==null?"Main-process loaded standard DEX only.":engine.optString("coverage","Main-process loaded standard DEX only."))+" Original base/split APK files are byte-preserved package copies, separate from runtime DEX. No all-code coverage or rebuilt installable APK claim.");
        receipt.put("status",cancelled?"failed":engineVerified&&packageVerified?"verified":validRuntime>0?"partial":"failed");
        receipt.put("error",validationError);receipt.put("engineReport",engine==null?JSONObject.NULL:engine);receipt.put("packageCopyReport",packageReport==null?JSONObject.NULL:packageReport);
        List<ReceiptArchive.Entry> entries=Collections.emptyList();
        try {entries=ReceiptArchive.inventory(stage);}catch(IOException empty){if(!origins.isEmpty())validationError=appendError(validationError,empty.getMessage());}
        JSONArray inventory=new JSONArray();long bytes=0;
        for(ReceiptArchive.Entry entry:entries){
            JSONObject item=new JSONObject();item.put("name",entry.name);item.put("bytes",entry.bytes);item.put("sha256",entry.sha256);
            JSONObject origin=origins.get(entry.name);
            if(origin==null)throw new IOException("Unclassified export stage file: "+entry.name);
            Iterator<String> keys=origin.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals("name")&&!key.equals("bytes")&&!key.equals("sha256"))item.put(key,origin.get(key));}
            inventory.put(item);bytes=VerifiedCopies.add(bytes,entry.bytes);
        }
        receipt.put("artifacts",inventory);receipt.put("artifactBytes",bytes);
        if(!entries.isEmpty()) {
            File zip=new File(output,"export.zip");
            try {
                VerifiedCopies.requireArchiveCapacity(bytes,0,stage.getUsableSpace());
                ReceiptArchive.create(stage,zip,entries);receipt.put("archiveVerified",true);receipt.put("zipBytes",zip.length());receipt.put("zipSha256",ReceiptArchive.digest(zip));
            } catch(Exception invalid) {receipt.put("status","failed");receipt.put("archiveVerified",false);validationError=appendError(validationError,"Archive verification failed: "+invalid.getMessage());zip.delete();}
        } else receipt.put("archiveVerified",false);
        receipt.put("error",validationError);
        write(new File(output,"report.json"),receipt.toString(2));
        removeTree(stage);
        Receipt result=new Receipt();result.directory=output;result.report=receipt;return result;
    }
    private static String appendError(String previous,String next){return previous==null||previous.isEmpty()?next:next==null||next.isEmpty()?previous:previous+"; "+next;}
    private static void removeTree(File file) {
        try {if(!file.getAbsoluteFile().equals(file.getCanonicalFile())){file.delete();return;}}catch(IOException invalid){return;}
        File[] children=file.listFiles();if(children!=null)for(File child:children)removeTree(child);file.delete();
    }
    public void verify(Receipt receipt) throws Exception {
        JSONObject fresh = read(new File(receipt.directory,"report.json"));
        if (!fresh.optBoolean("archiveVerified")) throw new IOException("This result has a diagnostic report only");
        File zip = new File(receipt.directory,"export.zip");
        if (zip.length()!=fresh.getLong("zipBytes") || !ReceiptArchive.digest(zip).equals(fresh.getString("zipSha256")))
            throw new IOException("Published ZIP size or hash mismatch");
        List<ReceiptArchive.Entry> entries = new ArrayList<>(); JSONArray items = fresh.getJSONArray("artifacts");
        for (int i=0;i<items.length();i++) { JSONObject item=items.getJSONObject(i); entries.add(new ReceiptArchive.Entry(item.getString("name"),item.getLong("bytes"),item.getString("sha256"))); }
        ReceiptArchive.verify(zip,entries); receipt.report=fresh;
    }
    public List<Receipt> history() {
        List<Receipt> out=new ArrayList<>(); File[] directories=exports.listFiles();
        if (directories!=null) for (File directory : directories) try {
            Receipt receipt=new Receipt();receipt.directory=directory;receipt.report=read(new File(directory,"report.json"));out.add(receipt);
        } catch (Exception ignored) { }
        Collections.sort(out,(a,b)->Long.compare(b.report.optLong("createdAt"),a.report.optLong("createdAt")));return out;
    }
    public static JSONObject read(File file) throws Exception {
        if (file.length() > 4*1024*1024) throw new IOException("Report exceeds 4 MiB");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];
        try(InputStream in=new FileInputStream(file)) { int n;while((n=in.read(buffer))!=-1) { out.write(buffer,0,n);if(out.size()>4*1024*1024)throw new IOException("Report exceeds 4 MiB"); } }
        return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
    }
    static void write(File file,String content) throws IOException {
        File temporary=new File(file.getPath()+".partial");
        try(FileOutputStream out=new FileOutputStream(temporary)) { out.write(content.getBytes(StandardCharsets.UTF_8));out.getFD().sync(); }
        if(!temporary.renameTo(file))throw new IOException("Could not publish export receipt");
    }
}
