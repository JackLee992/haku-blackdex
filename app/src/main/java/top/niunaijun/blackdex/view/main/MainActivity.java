package top.niunaijun.blackdex.view.main;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.io.InterruptedIOException;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import top.niunaijun.blackbox.BlackDexCore;
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackdex.R;
import top.niunaijun.blackdex.data.ExportStore;
import top.niunaijun.blackdex.data.PackageSnapshot;

/** Native Haku container host. Capture receipts never imply a rebuilt, installable APK. */
public final class MainActivity extends Activity {
    private static final int CREAM=0xfffff8ee, INK=0xff513a2e, CHESTNUT=0xff865137, MUTED=0xff806e60;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout root, content;
    private TextView status, sizes, log;
    private ProgressBar progress;
    private Button cancelButton;
    private File exportStage;
    private volatile boolean cancelRequested,cancelConfirmed;
    private boolean cancelSent;
    private long originalBytes;
    private List<ApplicationInfo> applications=new ArrayList<>();
    private ExportStore store;
    private ExportStore.Receipt receipt;
    private String selectedPackage, selectedName, sessionPath;
    private String screen="applications";
    private long startedAt;
    private boolean capturing, destroyed;
    private volatile boolean finishing;
    private final StringBuilder messages=new StringBuilder();
    private final IBDumpMonitor monitor=new IBDumpMonitor.Stub() {
        @Override public void onDump(DumpResult result) {
            if(result==null)return;
            String pkg=result.packageName, directory=result.dir, message=result.msg;
            boolean running=result.isRunning(), success=result.isSuccess(); int current=result.currProcess,total=result.totalProcess;
            post(()->accept(pkg,directory,message,running,success,current,total));
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=new ExportStore(getFilesDir());
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(10),dp(18),0);root.setBackgroundColor(CREAM);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mascot=new ImageView(this);mascot.setImageResource(R.drawable.haku_chestnut);mascot.setContentDescription("Haku chestnut mascot");header.addView(mascot,new LinearLayout.LayoutParams(dp(66),dp(66)));
        LinearLayout titles=column();titles.addView(label("Haku",30,true));titles.addView(label("BlackDex · development preview",12,false));header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        LinearLayout navigation=new LinearLayout(this);navigation.addView(button("Applications",R.id.haku_applications,this::showApplications),new LinearLayout.LayoutParams(0,dp(48),1));navigation.addView(button("Exports",R.id.haku_exports,this::showHistory),new LinearLayout.LayoutParams(0,dp(48),1));root.addView(navigation);
        content=column();root.addView(content,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        BlackDexCore.get().registerDumpMonitor(monitor);
        SharedPreferences saved=getSharedPreferences("capture",MODE_PRIVATE);
        if(saved.getBoolean("active",false)) {
            selectedPackage=saved.getString("package","");selectedName=saved.getString("name",selectedPackage);sessionPath=saved.getString("directory",null);startedAt=saved.getLong("startedAt",0);
            cancelRequested=saved.getBoolean("cancelRequested",false);cancelConfirmed=saved.getBoolean("cancelConfirmed",false);
            try{exportStage=store.savedStage(saved.getString("stage",null));}catch(Exception ignored){exportStage=null;}
            capturing=true;showCapture();append("Reconnected to saved capture session.");
            if(sessionPath==null) finishCapture(false,"Capture interrupted before an engine session was reported");
            else WORK.execute(()->{
                try {
                    JSONObject report=ExportStore.read(new File(sessionPath,"capture-report.json"));
                    String engineStatus=report.optString("status");
                    post(()->{ if(!"running".equals(engineStatus))finishCapture("verified".equals(engineStatus),report.optString("error"));else {armTimeout();if(cancelRequested)requestEngineCancel();} });
                }catch(Exception e){post(this::armTimeout);}
            });
        } else showApplications();
        loadApplications();
    }
    private void loadApplications() {
        WORK.execute(()->{
            List<ApplicationInfo> list=new ArrayList<>(getPackageManager().getInstalledApplications(0));
            list.removeIf(app->app.packageName.equals(getPackageName()));
            Collections.sort(list,(a,b)->a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
            post(()->{applications=list;if(!capturing&&"applications".equals(screen))showApplications();});
        });
    }
    private void showApplications() {
        if(capturing){showCapture();return;} screen="applications";receipt=null;content.removeAllViews();
        TextView intro=label("Choose an app to capture in the container. Runtime capture is limited to loaded standard DEX in the main process.",14,false);intro.setPadding(0,dp(12),0,dp(12));content.addView(intro);
        EditText search=new EditText(this);search.setId(R.id.haku_search);search.setSingleLine();search.setTextColor(INK);search.setTextSize(16);search.setHint("Search applications");search.setContentDescription("Search applications");content.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView count=label(applications.size()+" installed applications · on-device export",12,false);content.addView(count);
        ListView list=new ListView(this);list.setId(R.id.haku_application_list);list.setDividerHeight(dp(1));content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        final List<ApplicationInfo> filtered=new ArrayList<>(applications);
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return filtered.size();}public Object getItem(int n){return filtered.get(n);}public long getItemId(int n){return n;}
            public View getView(int n,View reuse,android.view.ViewGroup parent){
                ApplicationInfo app=filtered.get(n);LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(12),dp(2),dp(12));
                ImageView icon=new ImageView(MainActivity.this);icon.setImageDrawable(app.loadIcon(getPackageManager()));row.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
                LinearLayout detail=column();detail.setPadding(dp(12),0,dp(4),0);detail.addView(label(app.loadLabel(getPackageManager()).toString(),16,true));detail.addView(label(app.packageName,11,false));
                detail.addView(label(bytes(packageBytes(app))+" · "+((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0?"System app":"Installed app"),11,false));row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));row.addView(label("›",25,false));return row;
            }
        };list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,position,id)->confirm(filtered.get(position)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filtered.clear();String query=s.toString().toLowerCase(Locale.ROOT);for(ApplicationInfo app:applications)if(app.packageName.toLowerCase(Locale.ROOT).contains(query)||app.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT).contains(query))filtered.add(app);adapter.notifyDataSetChanged();count.setText(filtered.size()+" applications · on-device export");}public void afterTextChanged(Editable e){}});
    }
    private long packageBytes(ApplicationInfo app){long total=new File(app.sourceDir).length();if(app.splitSourceDirs!=null)for(String path:app.splitSourceDirs)total+=new File(path).length();return total;}
    private void confirm(ApplicationInfo app){
        if(Build.VERSION.SDK_INT!=33){alert("Android 13 preview","This container capture preview requires Android 13 (API 33). Current API: "+Build.VERSION.SDK_INT);return;}
        new AlertDialog.Builder(this).setTitle("Capture "+app.loadLabel(getPackageManager())+"?")
            .setMessage(app.packageName+"\n\nOriginal package: "+bytes(packageBytes(app))+"\n\nHaku installs a private container copy and starts it for capture. Use apps you own or are authorized to inspect. The ZIP includes unchanged original base/split APK copies, separate runtime DEX, and reports. It is not a rebuilt installable APK.")
            .setNegativeButton("Cancel",null).setPositiveButton("Start capture",(d,w)->startCapture(app)).show();
    }
    private void startCapture(ApplicationInfo app){
        if(capturing)return;
        try{exportStage=store.newStage();}catch(Exception error){alert("Export preparation failed",error.getMessage());return;}
        selectedPackage=app.packageName;selectedName=app.loadLabel(getPackageManager()).toString();sessionPath=null;startedAt=System.currentTimeMillis();
        capturing=true;finishing=false;cancelRequested=false;cancelConfirmed=false;cancelSent=false;originalBytes=0;receipt=null;messages.setLength(0);saveActive();showCapture();append("Copying and verifying the selected application's original APK files…");armTimeout();
        final String pkg=selectedPackage;final File stage=exportStage;final long attempt=startedAt;
        WORK.execute(()->{
            try{
                final long[] lastUpdate={0};
                PackageSnapshot snapshot=PackageSnapshot.create(stage,pkg,getPackageManager(),()->cancelRequested||finishing,(name,copied,total)->{
                    long now=SystemClock.elapsedRealtime();if(copied!=total&&now-lastUpdate[0]<250)return;lastUpdate[0]=now;
                    post(()->{if(!capturing||finishing||startedAt!=attempt)return;status.setText("Copying original APK: "+name);sizes.setText("Original package: "+bytes(copied)+" / "+bytes(total));progress.setIndeterminate(false);progress.setMax(1000);progress.setProgress(total==0?0:(int)(copied*1000/total));});
                });
                originalBytes=snapshot.bytes();
                if(finishing)return;
                if(cancelRequested){cancelConfirmed=true;throw new InterruptedIOException("Capture cancelled by user");}
                post(()->{append("Original package copies verified: "+bytes(originalBytes));status.setText("Starting private container…");progress.setIndeterminate(true);});
                // The last check is adjacent to the engine call: cancelled preparation cannot launch a target.
                if(finishing)return;
                if(cancelRequested){cancelConfirmed=true;throw new InterruptedIOException("Capture cancelled by user");}
                InstallResult installed=BlackDexCore.get().dumpDex(pkg);
                if(installed==null||!installed.success)post(()->finishCapture(false,"Container install or launch failed. No verified capture was reported."));
            }catch(InterruptedIOException cancelled){if(cancelRequested)cancelConfirmed=true;post(()->finishCapture(false,cancelRequested?"Capture cancelled by user":"Capture preparation stopped"));}
            catch(Throwable error){post(()->finishCapture(false,"Export preparation or container start failed: "+error.getClass().getSimpleName()+": "+error.getMessage()));}
        });
    }
    private void saveActive(){getSharedPreferences("capture",MODE_PRIVATE).edit().putBoolean("active",capturing).putString("package",selectedPackage).putString("name",selectedName).putString("directory",sessionPath).putLong("startedAt",startedAt).putString("stage",exportStage==null?null:exportStage.getName()).putBoolean("cancelRequested",cancelRequested).putBoolean("cancelConfirmed",cancelConfirmed).apply();}
    private void cancelCapture(){
        if(!capturing||finishing||cancelRequested)return;cancelRequested=true;saveActive();
        if(cancelButton!=null){cancelButton.setText("Cancelling…");cancelButton.setEnabled(false);}
        append("Cancellation requested for this capture attempt.");
        if(sessionPath!=null)requestEngineCancel();
        else {status.setText("Cancelling preparation…");append("If container launch has begun, cancellation waits for its exact session identifier.");}
    }
    private void requestEngineCancel(){
        if(!capturing||finishing||cancelSent||sessionPath==null)return;
        final String pkg=selectedPackage,path=sessionPath,id=new File(sessionPath).getName();final long attempt=startedAt;
        cancelSent=true;
        WORK.execute(()->{
            try {
                boolean accepted=BlackDexCore.get().cancelCapture(pkg,id);
                if(accepted)cancelConfirmed=true;
                post(()->{if(startedAt!=attempt)return;saveActive();append(accepted?"Exact capture session cancelled; waiting for its terminal report.":"Session already ended; retaining its terminal result.");});
                if(accepted) {
                    // A persisted terminal report is a fallback only for this exact cancelled session.
                    JSONObject report=ExportStore.read(new File(path,"capture-report.json"));
                    if(id.equals(report.getString("sessionId"))&&pkg.equals(report.getString("packageName"))&&"failed".equals(report.getString("status")))
                        post(()->{if(capturing&&!finishing&&startedAt==attempt&&path.equals(sessionPath))finishCapture(false,report.optString("error","Capture cancelled by user"));});
                }
            }catch(Exception error){post(()->{if(capturing&&!finishing&&startedAt==attempt){cancelSent=false;cancelRequested=false;saveActive();if(cancelButton!=null){cancelButton.setEnabled(true);cancelButton.setText("Cancel capture");}append("Cancellation failed: "+error.getMessage());}});}
        });
    }
    private void showCapture(){
        screen="capture";content.removeAllViews();content.addView(label(selectedName==null?"Capture":selectedName,24,true));content.addView(label(selectedPackage==null?"":selectedPackage,12,false));
        status=label(finishing?"Verifying export…":"Waiting for engine stage…",18,true);status.setId(R.id.haku_capture_status);status.setPadding(0,dp(20),0,dp(10));content.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);content.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        sizes=label("Session artifacts: 0 bytes",13,false);sizes.setId(R.id.haku_capture_bytes);sizes.setPadding(0,dp(12),0,dp(12));content.addView(sizes);
        content.addView(label("Application launch → Activity launch → runtime DEX validation → ZIP readback",12,false));
        ScrollView scroll=new ScrollView(this);log=label(messages.toString(),12,false);log.setId(R.id.haku_capture_log);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setPadding(dp(12),dp(12),dp(12),dp(12));log.setBackground(card());scroll.addView(log);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        cancelButton=button(cancelRequested?"Cancelling…":"Cancel capture",R.id.haku_cancel,this::cancelCapture);cancelButton.setEnabled(!finishing&&!cancelRequested);content.addView(cancelButton);
        content.addView(label("Capture continues while the container app is visible. Return to Haku to inspect or cancel this session.",12,false));
    }
    private void accept(String pkg,String directory,String message,boolean running,boolean success,int current,int total){
        if(!capturing||finishing||!Objects.equals(selectedPackage,pkg))return;
        try{
            File exact=store.session(selectedPackage,directory,startedAt);
            if(sessionPath!=null&&!new File(sessionPath).getCanonicalFile().equals(exact))return;
            sessionPath=exact.getAbsolutePath();saveActive();if(cancelRequested&&running)requestEngineCancel();
        }catch(Exception e){if(!running)finishCapture(false,"Rejected engine session: "+e.getMessage());return;}
        if(status==null)showCapture();status.setText(message==null?"Capturing runtime DEX…":message);append(message==null?"Engine progress":message);
        progress.setIndeterminate(total<=0);if(total>0){progress.setMax(total);progress.setProgress(current);}
        if(running){WORK.execute(()->{long size=directoryBytes(new File(directory));post(()->{if(sizes!=null&&capturing)sizes.setText("Session artifacts: "+bytes(size)+(total>0?" · "+current+" / "+total+" engine units":""));});});}
        else finishCapture(success,message);
    }
    private void armTimeout(){final long attempt=startedAt;long remaining=Math.max(0,attempt+180000-System.currentTimeMillis());main.postDelayed(()->{if(capturing&&!finishing&&startedAt==attempt)finishCapture(false,"No terminal engine result within 180 seconds. Existing files do not count as capture success.");},remaining);}
    private void finishCapture(boolean success,String error){
        if(!capturing||finishing)return;finishing=true;if(cancelButton!=null)cancelButton.setEnabled(false);status.setText("Verifying original APKs, runtime DEX and ZIP bytes…");progress.setIndeterminate(true);append("Independent archive readback started.");
        final String pkg=selectedPackage,path=sessionPath;final long start=startedAt;final File stage=exportStage;
        WORK.execute(()->{
            try{File session=null;String failure=error;
                try{session=path==null?null:store.session(pkg,path,start);}catch(Exception invalid){failure="Rejected engine session: "+invalid.getMessage();}
                ExportStore.Receipt result=store.finish(pkg,session,stage,success,failure,start,cancelConfirmed);post(()->{capturing=false;finishing=false;saveActive();receipt=result;showResult(result);});}
            catch(Exception e){post(()->{capturing=false;finishing=false;saveActive();status.setText("Export failed");progress.setIndeterminate(false);append(e.toString());content.addView(button("Back to applications",R.id.haku_done,this::showApplications));});}
        });
    }
    private void showResult(ExportStore.Receipt result){
        screen="result";receipt=result;content.removeAllViews();String title=result.report.optBoolean("cancelled")?"Capture cancelled":"verified".equals(result.status())?"Capture verified":"partial".equals(result.status())?"Partial export":"Capture failed";
        TextView heading=label(title,26,true);heading.setId(R.id.haku_result_status);heading.setPadding(0,dp(18),0,dp(14));content.addView(heading);content.addView(label(result.packageName(),13,false));
        JSONObject report=result.report;
        String detail="Original APK copies: "+bytes(report.optLong("packageCopyBytes"))+"\nRuntime DEX: "+bytes(report.optLong("runtimeDexBytes"))+"\nArchived files: "+bytes(report.optLong("artifactBytes"))+"\nZIP: "+bytes(report.optLong("zipBytes"))+"\nArchive readback: "+(report.optBoolean("archiveVerified")?"sizes + SHA-256 verified":"no verified archive")+"\n\n"+report.optString("coverage")+"\n\n"+report.optString("error");
        TextView body=label(detail,14,false);body.setId(R.id.haku_result_detail);body.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(result.hasZip())content.addView(button("Verify ZIP again",R.id.haku_verify,()->verify(result)));
        content.addView(button(result.hasZip()?"Share ZIP and report":"Share diagnostic report",R.id.haku_share,()->share(result)));
        content.addView(button("Done",R.id.haku_done,this::showApplications));
    }
    private void verify(ExportStore.Receipt result){
        Toast.makeText(this,"Reading archive bytes…",Toast.LENGTH_SHORT).show();WORK.execute(()->{try{store.verify(result);post(()->new AlertDialog.Builder(this).setTitle("ZIP verified").setMessage("Every archived file matches its receipt size and SHA-256. Capture scope remains limited to the runtime artifacts listed in the report.").setPositiveButton("OK",null).show());}catch(Exception e){post(()->alert("Verification failed",e.getMessage()));}});
    }
    private void share(ExportStore.Receipt result){
        WORK.execute(()->{try{if(result.hasZip())store.verify(result);post(()->{
            ArrayList<Uri> files=new ArrayList<>();if(result.hasZip())files.add(uri(result,"export.zip"));files.add(uri(result,"report.json"));
            Intent send=new Intent(Intent.ACTION_SEND_MULTIPLE);send.setType(result.hasZip()?"application/zip":"application/json");send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,files);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clips=ClipData.newUri(getContentResolver(),"Haku export",files.get(0));for(int i=1;i<files.size();i++)clips.addItem(new ClipData.Item(files.get(i)));send.setClipData(clips);
            try{startActivity(Intent.createChooser(send,"Share Haku export"));}catch(ActivityNotFoundException e){alert("No share application","Install a compatible file receiver to share this export.");}
        });}catch(Exception e){post(()->alert("Share blocked by verification",e.getMessage()));}});
    }
    private Uri uri(ExportStore.Receipt receipt,String file){return new Uri.Builder().scheme("content").authority(getPackageName()+".exports").appendPath(receipt.directory.getName()).appendPath(file).build();}
    private void showHistory(){
        if(capturing){showCapture();return;}screen="history";content.removeAllViews();content.addView(label("Exports",25,true));content.addView(label("Private on-device history · verified again before sharing",12,false));
        ListView list=new ListView(this);list.setId(R.id.haku_export_list);content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        WORK.execute(()->{List<ExportStore.Receipt> history=store.history();post(()->{
            if(!"history".equals(screen))return;
            if(history.isEmpty()){content.addView(label("No exports yet. Choose an application to begin.",14,false));return;}
            list.setAdapter(new BaseAdapter(){public int getCount(){return history.size();}public Object getItem(int n){return history.get(n);}public long getItemId(int n){return n;}public View getView(int n,View v,android.view.ViewGroup p){ExportStore.Receipt item=history.get(n);LinearLayout row=column();row.setPadding(0,dp(14),0,dp(14));row.addView(label(item.packageName(),15,true));row.addView(label(item.status().toUpperCase(Locale.ROOT)+" · "+bytes(item.report.optLong("zipBytes")),12,false));row.addView(label(DateFormat.getDateTimeInstance().format(new Date(item.report.optLong("createdAt"))),12,false));return row;}});list.setOnItemClickListener((p,v,n,id)->showResult(history.get(n)));
        });});
    }
    private void post(Runnable action){main.post(()->{if(!destroyed)action.run();});}
    private void append(String message){if(message==null||message.isEmpty())return;if(messages.length()>30000)messages.delete(0,10000);messages.append(message).append('\n');if(log!=null)log.setText(messages.toString());}
    private long directoryBytes(File directory){long total=0;File[] files=directory.listFiles();if(files!=null)for(File file:files)total+=file.isDirectory()?directoryBytes(file):file.length();return total;}
    private String bytes(long value){return String.format(Locale.US,"%,d bytes",value);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private TextView label(String text,int size,boolean bold){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(bold?INK:MUTED);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);view.setLineSpacing(dp(2),1);return view;}
    private Button button(String text,int id,Runnable action){Button button=new Button(this);button.setId(id);button.setText(text);button.setAllCaps(false);button.setTextColor(CHESTNUT);button.setOnClickListener(v->action.run());return button;}
    private GradientDrawable card(){GradientDrawable shape=new GradientDrawable();shape.setColor(0xfff3e7d7);shape.setCornerRadius(dp(16));return shape;}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private void alert(String title,String message){if(!destroyed)new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
    @Override public void onBackPressed(){if(capturing){showCapture();return;}showApplications();}
    @Override protected void onDestroy(){destroyed=true;BlackDexCore.get().unregisterDumpMonitor(monitor);main.removeCallbacksAndMessages(null);super.onDestroy();}
}
