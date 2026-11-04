package com.wilde.t3tv;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int BG=0xff0b111b, PANEL=0xff151e2b, MUTED=0xff9baabd, WHITE=0xfff2f6fb, GREEN=0xffb7f876, AMBER=0xffffce7a;
    private LinearLayout root, environmentRow, taskList, tabs;
    private TextView connection, summary, clock, section;
    private String selectedEnvironment="all", filter="attention", lastSignature="";
    private ScrollView scroll;
    private boolean listening;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (saved!=null) { selectedEnvironment=saved.getString("environment","all"); filter=saved.getString("filter","attention"); }
        build();
        if (!new File(getFilesDir(),"connection.json").exists()) {
            connection.setText("Setup required · install the bridge connection file");
            new AlertDialog.Builder(this).setTitle("Connect your environments")
                .setMessage("This private dashboard needs its HTTPS bridge address, certificate and access token. Run scripts/install.sh from the project on your computer to configure this TV.")
                .setPositiveButton("OK",null).show();
            return;
        }
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
        getSharedPreferences("dashboard",0).edit().putBoolean("monitor",true).apply();
        startForegroundService(new Intent(this,MonitorService.class));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!listening) { registerReceiver(receiver,new IntentFilter(MonitorService.UPDATED),Context.RECEIVER_NOT_EXPORTED); listening=true; }
        render();
    }
    @Override protected void onPause() { if(listening) {unregisterReceiver(receiver);listening=false;} super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("environment",selectedEnvironment);state.putString("filter",filter); }

    void build() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(40),dp(20),dp(40),dp(20));setContentView(root);
        LinearLayout top=row();
        TextView logo=text("T3",28,GREEN);logo.setTypeface(null,Typeface.BOLD);top.addView(logo);
        TextView brand=text("  /  MISSION CONTROL",13,MUTED);top.addView(brand);
        Space space=new Space(this);top.addView(space,new LinearLayout.LayoutParams(0,1,1));
        clock=text("",13,MUTED);top.addView(clock);
        Button settings=button("Settings", "settings",this::settings);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(100),dp(38));sp.leftMargin=dp(18);top.addView(settings,sp);root.addView(top);
        LinearLayout titleRow=row();titleRow.setPadding(0,dp(12),0,dp(3));
        TextView title=text("Your agents, at a glance.",29,WHITE);title.setTypeface(null,Typeface.BOLD);titleRow.addView(title);root.addView(titleRow);
        summary=text("Connecting to your environments…",14,MUTED);root.addView(summary);
        environmentRow=row(); environmentRow.setPadding(0,dp(12),0,dp(12));root.addView(environmentRow);
        tabs=row();root.addView(tabs);buildTabs();
        section=text("NEEDS ATTENTION",11,MUTED);section.setPadding(0,dp(10),0,dp(6));root.addView(section);
        scroll=new ScrollView(this);scroll.setFillViewport(false);taskList=new LinearLayout(this);taskList.setOrientation(LinearLayout.VERTICAL);scroll.addView(taskList);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row();footer.setPadding(0,dp(10),0,0);connection=text("Connecting…",11,MUTED);footer.addView(connection,new LinearLayout.LayoutParams(0,-2,1));footer.addView(text("↑ ↓ ← → Navigate     OK Details     Back TV",11,MUTED));root.addView(footer);
    }

    void buildTabs() {
        tabs.removeAllViews();
        for(String key:new String[]{"attention","working","all"}) {
            String name=key.equals("attention")?"Needs attention":key.equals("working")?"Working":"All tasks";
            Button b=button(name,"tab-"+key,()->{filter=key;lastSignature="";buildTabs();render();View focus=findTag(tabs,"tab-"+key);if(focus!=null)focus.requestFocus();});
            b.setTextColor(key.equals(filter)?GREEN:MUTED);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(155),dp(38));lp.rightMargin=dp(10);tabs.addView(b,lp);
        }
    }

    void render() {
        clock.setText(new SimpleDateFormat("EEE  h:mm a",Locale.getDefault()).format(new Date()));
        connection.setText(MonitorService.error!=null?MonitorService.error:"● Live · updates automatically   ·   "+(getSharedPreferences("dashboard",0).getBoolean("alerts",true)?"Alerts on":"Alerts paused"));
        connection.setTextColor(MonitorService.error==null?GREEN:AMBER);
        JSONObject data=MonitorService.snapshot;if(data==null)return;
        try {
            JSONArray envs=data.getJSONArray("environments");
            String signature=envs.toString()+selectedEnvironment+filter;
            if(signature.equals(lastSignature))return;
            lastSignature=signature;
            String focused=getCurrentFocus()!=null?String.valueOf(getCurrentFocus().getTag()):"";
            int oldScroll=scroll.getScrollY();
            ArrayList<JSONObject> all=new ArrayList<>();int working=0,attention=0,online=0;
            environmentRow.removeAllViews();
            addEnvironment("all","All environments",envs.length()+" environments",selectedEnvironment.equals("all"),true);
            for(int i=0;i<envs.length();i++) {
                JSONObject env=envs.getJSONObject(i);boolean available=env.getBoolean("online");if(available)online++;
                JSONArray tasks=env.getJSONArray("threads");int envAttention=0;
                for(int j=0;j<tasks.length();j++) {
                    JSONObject t=new JSONObject(tasks.getJSONObject(j).toString());t.put("environment",env.getString("label"));t.put("environmentId",env.getString("id"));
                    if(isAttention(t.optString("status")))envAttention++;
                    if(selectedEnvironment.equals("all")||selectedEnvironment.equals(env.getString("id"))) { all.add(t);if(isAttention(t.optString("status")))attention++;if(isWorking(t.optString("status")))working++; }
                }
                addEnvironment(env.getString("id"),env.getString("label"),available?envAttention+" need attention  ·  "+tasks.length()+" tasks":"Offline · reconnecting",selectedEnvironment.equals(env.getString("id")),available);
            }
            summary.setText(attention+" need your attention   /   "+working+" working   /   "+online+" of "+envs.length()+" environments online");
            all.sort((a,b)->{int p=Integer.compare(priority(a.optString("status")),priority(b.optString("status")));return p!=0?p:b.optString("updatedAt").compareTo(a.optString("updatedAt"));});
            taskList.removeAllViews();int shown=0;LinearLayout pair=null;
            for(JSONObject t:all) {
                String status=t.optString("status");
                if(filter.equals("attention")&&!isAttention(status))continue;
                if(filter.equals("working")&&!isWorking(status))continue;
                if(shown%2==0) {pair=row();taskList.addView(pair);}
                LinearLayout card=taskCard(t);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(94),1);lp.bottomMargin=dp(10);if(shown%2==0)lp.rightMargin=dp(10);pair.addView(card,lp);shown++;
            }
            if(shown%2==1)pair.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1));
            section.setText((filter.equals("attention")?"NEEDS ATTENTION":filter.equals("working")?"WORK IN PROGRESS":"ALL ACTIVE THREADS")+"  /  "+shown);
            if(shown==0) {TextView empty=text(online==0?"Your environments are offline.\nThe dashboard will reconnect automatically.":filter.equals("attention")?"All clear.\nWe’ll let you know when an agent needs you.":"Nothing here right now.",21,MUTED);empty.setPadding(dp(24),dp(24),dp(24),dp(24));taskList.addView(empty);}
            View restore=findTag(root,focused);if(restore!=null)restore.requestFocus();else if(!focused.isEmpty()&&focused.startsWith("task-")){View tab=findTag(tabs,"tab-"+filter);if(tab!=null)tab.requestFocus();}
            scroll.post(()->scroll.scrollTo(0,oldScroll));
        } catch(JSONException e) {connection.setText("Unexpected bridge data · check the bridge version");}
    }

    void addEnvironment(String id,String label,String detail,boolean selected,boolean online) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(15),dp(11),dp(15),dp(11));
        TextView name=text((online?"●  ":"○  ")+label,16,selected?GREEN:WHITE);name.setSingleLine(true);card.addView(name);TextView meta=text(detail,11,MUTED);meta.setPadding(0,dp(5),0,0);card.addView(meta);
        focusable(card,"env-"+id,()->{selectedEnvironment=id;lastSignature="";render();},selected);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(60),1);lp.rightMargin=dp(9);environmentRow.addView(card,lp);
    }

    LinearLayout taskCard(JSONObject task) {
        String status=task.optString("status");int accent=status.equals("waiting")?AMBER:status.equals("failed")?0xffffa49c:status.equals("completed")?GREEN:0xffa6caff;
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout meta=row();TextView state=text("●  "+MonitorService.statusLabel(status),11,accent);meta.addView(state,new LinearLayout.LayoutParams(0,-2,1));meta.addView(text(task.optString("environment"),10,MUTED));card.addView(meta);
        TextView title=text(task.optString("title"),17,WHITE);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(0,dp(6),0,dp(4));card.addView(title,new LinearLayout.LayoutParams(-1,0,1));
        TextView project=text(task.optString("project")+"   /   "+task.optString("model"),10,MUTED);project.setSingleLine(true);project.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(project);
        focusable(card,"task-"+task.optString("environmentId")+"-"+task.optString("id"),()->details(task),false);return card;
    }

    void details(JSONObject task) {
        startActivity(new Intent(this,ConversationActivity.class).putExtra("environmentId",task.optString("environmentId"))
            .putExtra("environment",task.optString("environment")).putExtra("threadId",task.optString("id")).putExtra("title",task.optString("title")));
    }

    void settings() {
        boolean alerts=getSharedPreferences("dashboard",0).getBoolean("alerts",true);
        String[] choices={alerts?"Pause task alerts":"Resume task alerts",Settings.canDrawOverlays(this)?"TV pop-ups: enabled · manage permission":"Enable pop-ups over TV", "Send a test alert", "Stop monitoring and close"};
        new AlertDialog.Builder(this).setTitle("Dashboard settings").setItems(choices,(dialog,which)->{
            if(which==0) {getSharedPreferences("dashboard",0).edit().putBoolean("alerts",!alerts).apply();render();}
            if(which==1) {
                try {startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
                catch(ActivityNotFoundException e) {new AlertDialog.Builder(this).setMessage("Open Android Settings → Apps → Special app access → Display over other apps → T3 Dashboard.").setPositiveButton("OK",null).show();}
            }
            if(which==2) startForegroundService(new Intent(this,MonitorService.class).setAction("TEST"));
            if(which==3) {getSharedPreferences("dashboard",0).edit().putBoolean("monitor",false).apply();stopService(new Intent(this,MonitorService.class));finish();}
        }).setNegativeButton("Close",null).show();
    }

    static boolean isAttention(String s){return s.equals("waiting")||s.equals("completed")||s.equals("failed");}
    static boolean isWorking(String s){return s.equals("running")||s.equals("queued")||s.equals("starting")||s.equals("preparing");}
    static int priority(String s){return s.equals("waiting")?0:s.equals("failed")?1:s.equals("completed")?2:isWorking(s)?3:4;}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    GradientDrawable background(boolean focused,boolean selected){GradientDrawable bg=new GradientDrawable();bg.setColor(focused?0xff263b38:PANEL);bg.setCornerRadius(dp(9));bg.setStroke(dp(focused?2:1),focused?GREEN:selected?0xff567544:0xff243142);return bg;}
    void focusable(View v,String tag,Runnable click,boolean selected){v.setTag(tag);v.setFocusable(true);v.setClickable(true);v.setBackground(background(false,selected));v.setOnClickListener(w->click.run());v.setOnFocusChangeListener((w,focused)->{w.setBackground(background(focused,selected));w.setElevation(focused?dp(5):0);});}
    Button button(String title,String tag,Runnable click){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setTextColor(WHITE);b.setAllCaps(false);b.setPadding(dp(10),0,dp(10),0);b.setMinHeight(0);b.setMinimumHeight(0);focusable(b,tag,click,false);return b;}
    View findTag(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findTag(group.getChildAt(i),tag);if(found!=null)return found;}}return null;}
}
