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
    private static final int DIM=0xff4d5a68;
    private LinearLayout root, environmentRow, taskList, tabs, banner;
    private TextView connection, clock, availability, bannerText, bannerAge, bannerDetail;
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
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(40),dp(20),dp(40),dp(18));setContentView(root);
        LinearLayout top=row();
        TextView logo=text("T3",26,GREEN);logo.setTypeface(null,Typeface.BOLD);top.addView(logo);
        TextView name=text("  Dashboard",26,WHITE);name.setTypeface(null,Typeface.BOLD);top.addView(name);
        Space space=new Space(this);top.addView(space,new LinearLayout.LayoutParams(0,1,1));
        clock=text("",13,MUTED);top.addView(clock);
        Button settings=button("Settings", "settings",this::settings);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(100),dp(38));sp.leftMargin=dp(18);top.addView(settings,sp);root.addView(top);
        LinearLayout envWrap=row();envWrap.setPadding(0,dp(14),0,dp(10));
        HorizontalScrollView envScroll=new HorizontalScrollView(this);envScroll.setHorizontalScrollBarEnabled(false);
        environmentRow=row();envScroll.addView(environmentRow);envWrap.addView(envScroll,new LinearLayout.LayoutParams(0,-2,1));
        availability=text("",13,MUTED);availability.setPadding(dp(14),0,0,0);envWrap.addView(availability);root.addView(envWrap);
        banner=new LinearLayout(this);banner.setOrientation(LinearLayout.VERTICAL);banner.setPadding(dp(16),dp(11),dp(16),dp(11));banner.setBackground(bannerBackground());banner.setVisibility(View.GONE);
        LinearLayout bannerRow=row();bannerText=text("",14,AMBER);bannerRow.addView(bannerText,new LinearLayout.LayoutParams(0,-2,1));
        bannerAge=text("",13,AMBER);bannerRow.addView(bannerAge);banner.addView(bannerRow);
        bannerDetail=text("",11,0xffc9b98a);bannerDetail.setPadding(0,dp(4),0,0);banner.addView(bannerDetail);
        LinearLayout.LayoutParams bannerLp=new LinearLayout.LayoutParams(-1,-2);bannerLp.bottomMargin=dp(8);root.addView(banner,bannerLp);
        tabs=row();tabs.setPadding(0,0,0,dp(10));root.addView(tabs);
        scroll=new ScrollView(this);scroll.setFillViewport(false);taskList=new LinearLayout(this);taskList.setOrientation(LinearLayout.VERTICAL);scroll.addView(taskList);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView waiting=text("Connecting to your environments…",18,MUTED);waiting.setPadding(dp(24),dp(24),dp(24),dp(24));taskList.addView(waiting);
        LinearLayout footer=row();footer.setPadding(0,dp(10),0,0);connection=text("Connecting…",11,MUTED);footer.addView(connection,new LinearLayout.LayoutParams(0,-2,1));footer.addView(text("D-pad Move focus     OK Open task     Back TV",11,MUTED));root.addView(footer);
    }

    void render() {
        clock.setText(new SimpleDateFormat("EEE  h:mm a",Locale.getDefault()).format(new Date()));
        int feed=MonitorService.feedState;
        JSONObject data=MonitorService.snapshot;
        if (data==null) {
            connection.setText(feed==DashboardState.STALE?"● Showing cached data":"○ Connecting to your environments…");
            connection.setTextColor(feed==DashboardState.STALE?AMBER:MUTED);
            availability.setText("");banner.setVisibility(View.GONE);
            return;
        }
        try {
            JSONArray envsJson=data.getJSONArray("environments");
            ArrayList<DashboardState.Env> envs=new ArrayList<>();
            for(int i=0;i<envsJson.length();i++) {
                JSONObject e=envsJson.getJSONObject(i);DashboardState.Env env=new DashboardState.Env();
                env.id=e.getString("id");env.label=e.getString("label");env.online=e.getBoolean("online");env.error=e.optString("error",null);
                JSONArray threads=e.getJSONArray("threads");
                for(int j=0;j<threads.length();j++) {
                    JSONObject t=threads.getJSONObject(j);DashboardState.Task task=new DashboardState.Task();
                    task.id=t.optString("id");task.environmentId=env.id;task.environment=env.label;task.title=t.optString("title");
                    task.project=t.optString("project");task.model=t.optString("model");task.status=t.optString("status");task.updatedAt=t.optString("updatedAt");
                    env.tasks.add(task);
                }
                envs.add(env);
            }
            if(!selectedEnvironment.equals("all")) {boolean found=false;for(DashboardState.Env env:envs)if(env.id.equals(selectedEnvironment))found=true;if(!found)selectedEnvironment="all";}
            DashboardState.View view=DashboardState.summarize(feed,MonitorService.lastSuccess,System.currentTimeMillis(),envs,selectedEnvironment,filter,getSharedPreferences("dashboard",0).getBoolean("alerts",true));
            if(view.banner!=null) {
                banner.setVisibility(View.VISIBLE);bannerText.setText(view.banner);bannerAge.setText(view.bannerAge==null?"":view.bannerAge);
                String detail=view.bannerDetail!=null?view.bannerDetail:feed==DashboardState.STALE?MonitorService.feedDetail:null;
                if(detail!=null){bannerDetail.setText(detail);bannerDetail.setVisibility(View.VISIBLE);}else bannerDetail.setVisibility(View.GONE);
            } else banner.setVisibility(View.GONE);
            availability.setText(view.availability);availability.setTextColor(feed==DashboardState.STALE?AMBER:MUTED);
            connection.setText(view.footer);connection.setTextColor(feed==DashboardState.LIVE?GREEN:feed==DashboardState.STALE?AMBER:MUTED);
            String signature=envsJson.toString()+"|"+feed+"|"+MonitorService.lastSuccess+"|"+selectedEnvironment+"|"+filter;
            if(signature.equals(lastSignature))return;
            lastSignature=signature;
            String focused=getCurrentFocus()!=null?String.valueOf(getCurrentFocus().getTag()):"";
            int oldScroll=scroll.getScrollY();
            environmentRow.removeAllViews();
            addEnvironment("all","All environments",true,selectedEnvironment.equals("all"),view.cached);
            for(DashboardState.Env env:envs)addEnvironment(env.id,env.label,env.online,selectedEnvironment.equals(env.id),view.cached);
            buildTabs(view);
            taskList.removeAllViews();
            for(DashboardState.Task task:view.tasks) {
                LinearLayout card=taskCard(task,view.cached);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);taskList.addView(card,lp);
            }
            if(view.empty!=null) {TextView empty=text(view.empty,18,MUTED);empty.setPadding(dp(24),dp(24),dp(24),dp(24));taskList.addView(empty);}
            View restore=findTag(root,focused);
            if(restore!=null)restore.requestFocus();
            else if(focused.startsWith("task-")) {View tab=findTag(tabs,"tab-"+filter);if(tab!=null)tab.requestFocus();}
            else if(focused.startsWith("env-")) {View env=findTag(environmentRow,"env-"+selectedEnvironment);if(env!=null)env.requestFocus();}
            scroll.post(()->scroll.scrollTo(0,oldScroll));
        } catch(JSONException e) {connection.setText("Unexpected bridge data · check the bridge version");connection.setTextColor(AMBER);}
    }

    void buildTabs(DashboardState.View view) {
        tabs.removeAllViews();
        for(String key:new String[]{"attention","working","all"}) {
            int count=key.equals("attention")?view.attention:key.equals("working")?view.working:view.all;
            String name=(key.equals("attention")?"Needs attention":key.equals("working")?"Working":"All tasks")+"  "+count;
            LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setPadding(dp(2),dp(2),dp(2),dp(2));
            boolean selected=key.equals(filter);
            tab.addView(text(name,15,selected?GREEN:MUTED));
            View underline=new View(this);underline.setBackgroundColor(selected?GREEN:Color.TRANSPARENT);
            LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,dp(3));up.topMargin=dp(6);tab.addView(underline,up);
            focusable(tab,"tab-"+key,()->{filter=key;lastSignature="";render();View focus=findTag(tabs,"tab-"+key);if(focus!=null)focus.requestFocus();},false);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(28);tabs.addView(tab,lp);
        }
    }

    void addEnvironment(String id,String label,boolean online,boolean selected,boolean cached) {
        LinearLayout chip=row();chip.setPadding(dp(14),dp(9),dp(14),dp(9));
        if(!id.equals("all")) {
            TextView dot=text("●",11,cached?MUTED:online?GREEN:AMBER);chip.addView(dot);
            TextView name=text("  "+label,15,selected?GREEN:WHITE);name.setSingleLine(true);chip.addView(name);
        } else {
            TextView name=text(label,15,selected?GREEN:WHITE);name.setSingleLine(true);chip.addView(name);
        }
        focusable(chip,"env-"+id,()->{selectedEnvironment=id;lastSignature="";render();},selected);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(10);environmentRow.addView(chip,lp);
    }

    LinearLayout taskCard(DashboardState.Task task,boolean cached) {
        String status=task.status;
        int accent=cached?DIM:status.equals("waiting")?AMBER:status.equals("failed")?0xffffa49c:status.equals("completed")?GREEN:0xffa6caff;
        LinearLayout card=row();card.setPadding(dp(14),dp(15),dp(18),dp(15));
        View bar=new View(this);GradientDrawable accentBar=new GradientDrawable();accentBar.setColor(accent);accentBar.setCornerRadius(dp(2));bar.setBackground(accentBar);
        LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(dp(4),-1);barLp.topMargin=dp(2);barLp.bottomMargin=dp(2);card.addView(bar,barLp);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),0,dp(10),0);card.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=text(task.title,21,WHITE);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);body.addView(title);
        String meta=task.project+"  /  "+task.environment+(task.model.isEmpty()?"":"  /  "+task.model);
        TextView project=text(meta,14,MUTED);project.setSingleLine(true);project.setEllipsize(android.text.TextUtils.TruncateAt.END);project.setPadding(0,dp(6),0,0);body.addView(project);
        TextView state=text(DashboardState.statusLabel(status),15,cached?MUTED:accent);card.addView(state);
        TextView chevron=text("›",18,MUTED);chevron.setPadding(dp(12),0,0,0);card.addView(chevron);
        focusable(card,"task-"+task.environmentId+"-"+task.id,()->details(task),false);return card;
    }

    void details(DashboardState.Task task) {
        startActivity(new Intent(this,ConversationActivity.class).putExtra("environmentId",task.environmentId)
            .putExtra("environment",task.environment).putExtra("threadId",task.id).putExtra("title",task.title));
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

    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    GradientDrawable background(boolean focused,boolean selected){GradientDrawable bg=new GradientDrawable();bg.setColor(focused?0xff263b38:PANEL);bg.setCornerRadius(dp(9));bg.setStroke(dp(focused?2:1),focused?GREEN:selected?0xff567544:0xff243142);return bg;}
    GradientDrawable bannerBackground(){GradientDrawable bg=new GradientDrawable();bg.setColor(0xff2b2417);bg.setCornerRadius(dp(9));bg.setStroke(dp(1),0xff5a4d2a);return bg;}
    void focusable(View v,String tag,Runnable click,boolean selected){v.setTag(tag);v.setFocusable(true);v.setClickable(true);v.setBackground(background(false,selected));v.setOnClickListener(w->click.run());v.setOnFocusChangeListener((w,focused)->{w.setBackground(background(focused,selected));w.setElevation(focused?dp(5):0);});}
    Button button(String title,String tag,Runnable click){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setTextColor(WHITE);b.setAllCaps(false);b.setPadding(dp(10),0,dp(10),0);b.setMinHeight(0);b.setMinimumHeight(0);focusable(b,tag,click,false);return b;}
    View findTag(View view,String tag){if(tag.equals(view.getTag()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findTag(group.getChildAt(i),tag);if(found!=null)return found;}}return null;}
}
