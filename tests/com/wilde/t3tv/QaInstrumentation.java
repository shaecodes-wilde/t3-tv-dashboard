package com.wilde.t3tv;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.content.res.*;
import android.util.DisplayMetrics;
import android.speech.RecognizerIntent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

// Installed only as a separate instrumentation APK. No fixture API is added to the app.
public class QaInstrumentation extends Instrumentation {
    String profile,head; int checks, failures; Activity active;
    ArrayList<String> log=new ArrayList<>();
    interface Work { void run() throws Exception; }
    public void onCreate(Bundle args){super.onCreate(args);profile=args.getString("profile","tv");head=args.getString("head","unrecorded");start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle saved){
        if(profile.equals("720p")){
            Resources r=activity.getResources();Configuration c=new Configuration(r.getConfiguration());
            DisplayMetrics m=new DisplayMetrics();m.setTo(r.getDisplayMetrics());
            c.densityDpi=213;c.screenWidthDp=960;c.screenHeightDp=540;
            m.densityDpi=213;m.density=213f/160;m.scaledDensity=m.density*c.fontScale;
            r.updateConfiguration(c,m);
        }
        super.callActivityOnCreate(activity,saved);
    }
    void ui(Work w){final RuntimeException[] error={null};runOnMainSync(()->{try{w.run();}catch(Exception e){error[0]=new RuntimeException(e);}});if(error[0]!=null)throw error[0];waitForIdleSync();}
    void check(String name,boolean ok){checks++;if(!ok)failures++;log.add((ok?"PASS ":"FAIL ")+name);}
    void close(){if(active!=null){Activity a=active;ui(a::finish);active=null;}}
    <T extends Activity>T open(Class<T> type, Intent intent){close();intent.setClass(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);active=startActivitySync(intent);waitForIdleSync();return type.cast(active);}
    void key(int code){sendKeyDownUpSync(code);waitForIdleSync();}
    String text(View v){String s=v instanceof TextView?((TextView)v).getText().toString():"";if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View c=((ViewGroup)v).getChildAt(i);if(c.getVisibility()==View.VISIBLE)s+="\n"+text(c);}return s;}
    String screen(){final String[] s={""};ui(()->s[0]=text(active.getWindow().getDecorView()));return s[0];}
    boolean visible(View view){Rect r=new Rect();return view!=null&&view.getGlobalVisibleRect(r)&&r.width()>=view.getWidth()-2&&r.height()>=view.getHeight()-2&&view.getWidth()>0&&view.getHeight()>0;}
    boolean dialogContains(String text){long until=SystemClock.uptimeMillis()+1500;do{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText(text).isEmpty())return true;SystemClock.sleep(20);}while(SystemClock.uptimeMillis()<until);return false;}
    View byText(View v,String label){if(v instanceof TextView&&((TextView)v).getText().toString().equals(label))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=byText(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    void shot(String name)throws Exception{
        waitForIdleSync();SystemClock.sleep(250);final Bitmap[] image={null};
        if(profile.equals("720p"))ui(()->{
            ViewGroup content=active.findViewById(android.R.id.content);View root=content.getChildAt(0);
            int w=root.getWidth(),h=root.getHeight();
            root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);
            image[0]=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image[0]));
            if(active instanceof MainActivity&&name.equals("02-dashboard")){
                MainActivity a=(MainActivity)active;
                check("720p native render shows every task filter",visible(a.findTag(root,"tab-attention"))&&visible(a.findTag(root,"tab-working"))&&visible(a.findTag(root,"tab-all")));
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
        });else image[0]=getUiAutomation().takeScreenshot();
        Bitmap b=image[0];if(b==null)throw new IOException("Native screenshot unavailable");File dir=new File(getTargetContext().getFilesDir(),"qa-"+profile);dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    JSONObject feed(boolean longText)throws Exception{
        JSONArray envs=new JSONArray();String[] states={"waiting","failed","completed","running","queued"};
        for(int i=0;i<(longText?8:2);i++){
            JSONArray tasks=new JSONArray();
            if(i<2)for(int j=0;j<(i==0?3:2);j++){int n=i==0?j:j+3;tasks.put(new JSONObject().put("id","t"+n).put("status",states[n]).put("title",longText?"Review the dashboard implementation with a long task name that must wrap without obscuring its status or the focus outline":"Sample task "+n).put("project","t3-tv-dashboard").put("model","Codex").put("updatedAt","2026-11-05T21:00:00Z"));}
            envs.put(new JSONObject().put("id","e"+i).put("label",longText?"environment-with-a-long-name-"+i:i==0?"workstation":"fedora").put("online",true).put("threads",tasks));
        }
        return new JSONObject().put("environments",envs);
    }
    void render(MainActivity a,JSONObject data,int state)throws Exception{ui(()->{MonitorService.snapshot=data;MonitorService.feedState=state;MonitorService.lastSuccess=System.currentTimeMillis()-(state==DashboardState.STALE?52000:0);a.render();});}
    Intent composeIntent(String thread,boolean running){return new Intent().putExtra("environmentId","e0").putExtra("environment","workstation").putExtra("threadId",thread).putExtra("title","Refine the dashboard layout").putExtra("active",running);}
    void dashboard()throws Exception{
        // No connection.json is created, so the monitoring service cannot contact any host.
        ui(()->{MonitorService.snapshot=null;MonitorService.feedState=DashboardState.CONNECTING;});
        MainActivity a=open(MainActivity.class,new Intent());key(KeyEvent.KEYCODE_BACK);
        check("initial load has a connecting indicator, not Live",screen().contains("Connecting")&&!screen().contains("● Live"));shot("01-connecting");
        JSONObject data=feed(false);render(a,data,DashboardState.LIVE);
        check("dashboard title and filter counts",screen().contains("Dashboard")&&screen().contains("Needs attention  3")&&screen().contains("Working  2")&&screen().contains("All tasks  5"));
        check("failure state is explicit",screen().contains("Failed")&&!screen().contains("Needs a look"));
        ui(()->{
            View tab=a.findTag(a.getWindow().getDecorView(),"tab-attention");
            check("every task filter is fully on screen",visible(tab)&&visible(a.findTag(a.getWindow().getDecorView(),"tab-working"))&&visible(a.findTag(a.getWindow().getDecorView(),"tab-all")));
            check("filter row stays compact (height "+((View)tab.getParent()).getHeight()+"px)",((View)tab.getParent()).getHeight()<a.dp(65));
            View task=a.findTag(a.getWindow().getDecorView(),"task-e0-t0");
            check("task viewport retains at least 250dp",((View)task.getParent().getParent()).getHeight()>=a.dp(250));
            tab.requestFocus();
        });
        for(String tab:new String[]{"working","all"}){
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            check("D-pad reaches visible "+tab+" filter",String.valueOf(a.getCurrentFocus().getTag()).equals("tab-"+tab)&&visible(a.getCurrentFocus()));
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            check("OK selects visible "+tab+" filter",String.valueOf(a.getCurrentFocus().getTag()).equals("tab-"+tab)&&visible(a.getCurrentFocus()));
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_CENTER);
        ui(()->a.findTag(a.getWindow().getDecorView(),"task-e0-t0").requestFocus());shot("02-dashboard");
        View original=a.getCurrentFocus();render(a,data,DashboardState.LIVE);
        check("unchanged successful poll preserves actual view identity",a.getCurrentFocus()==original);
        key(KeyEvent.KEYCODE_DPAD_DOWN);check("D-pad moves between task rows",String.valueOf(a.getCurrentFocus().getTag()).equals("task-e0-t1"));
        render(a,data,DashboardState.LIVE);check("polling preserves task focus",String.valueOf(a.getCurrentFocus().getTag()).equals("task-e0-t1"));
        render(a,data,DashboardState.STALE);String stale=screen();
        check("stale warning includes age and cached label",stale.contains("Bridge offline")&&stale.contains("52s ago")&&stale.contains("Showing cached data"));
        check("stale removes Live and online count",!stale.contains("● Live")&&!stale.contains("2 of 2 online"));shot("03-stale");
        check("focused task stays fully visible after banner change",visible(a.getCurrentFocus()));
        render(a,data,DashboardState.LIVE);check("identical task JSON recovers Live and online count",screen().contains("● Live")&&screen().contains("2 of 2 online")&&!screen().contains("Bridge offline"));
        JSONObject partial=feed(false);partial.getJSONArray("environments").getJSONObject(1).put("online",false).put("error","Fixture SSH connection unavailable");render(a,partial,DashboardState.LIVE);
        check("single offline environment is distinct from bridge loss",screen().contains("fedora is offline")&&screen().contains("1 of 2 online")&&screen().contains("● Live"));shot("04-one-offline");
        render(a,feed(true),DashboardState.LIVE);shot("05-long-titles-environments");
        check("focused task stays visible after title heights change",visible(a.getCurrentFocus()));
        ui(()->a.findTag(a.getWindow().getDecorView(),"env-e7").requestFocus());render(a,MonitorService.snapshot,DashboardState.LIVE);
        check("far environment chip stays focusable",String.valueOf(a.getCurrentFocus().getTag()).equals("env-e7"));shot("06-environment-overflow");
        render(a,new JSONObject().put("environments",new JSONArray()),DashboardState.LIVE);check("no-environment state is explicit",screen().contains("No environments configured."));shot("07-no-environments");
        JSONObject empty=feed(false);for(int i=0;i<2;i++)empty.getJSONArray("environments").getJSONObject(i).put("threads",new JSONArray());render(a,empty,DashboardState.LIVE);
        check("empty attention names the filter",screen().contains("No tasks need attention."));shot("08-empty-attention");
        ui(()->a.findTag(a.getWindow().getDecorView(),"tab-working").performClick());check("empty working names the filter",screen().contains("No working tasks."));shot("09-empty-working");
        for(int i=0;i<2;i++)empty.getJSONArray("environments").getJSONObject(i).put("online",false);render(a,empty,DashboardState.LIVE);
        check("all-offline state is explicit",screen().contains("No environments online."));shot("10-all-offline");
        JSONObject many=feed(false);JSONArray rows=many.getJSONArray("environments").getJSONObject(0).getJSONArray("threads");
        for(int i=3;i<16;i++)rows.put(new JSONObject(rows.getJSONObject(0).toString()).put("id","extra"+i).put("title","Additional sample task "+i));
        render(a,many,DashboardState.LIVE);ui(()->a.findTag(a.getWindow().getDecorView(),"tab-attention").performClick());
        ui(()->a.findTag(a.getWindow().getDecorView(),"task-e0-extra15").requestFocus());
        render(a,many,DashboardState.STALE);
        check("far focused task remains visible across a smaller viewport",visible(a.getCurrentFocus())&&String.valueOf(a.getCurrentFocus().getTag()).equals("task-e0-extra15"));shot("16-scroll-focus");
        rows.remove(rows.length()-1);render(a,many,DashboardState.LIVE);
        check("removed task falls back to a visible filter",String.valueOf(a.getCurrentFocus().getTag()).equals("tab-attention")&&visible(a.getCurrentFocus()));
        close();
    }
    void composer()throws Exception{
        final ComposeActivity a=open(ComposeActivity.class,composeIntent("qa-reply",true));
        check("active task retains three delivery choices",screen().contains("Send and continue")&&screen().contains("Queue follow-up")&&screen().contains("Update current task"));
        ui(()->a.fields.get(0).edit.setText("Use the darker background and keep the green focus ring."));
        check("typed draft has a persisted indicator",screen().contains("Draft saved on this TV")&&screen().contains("Ready to send"));
        ui(()->a.form.findViewWithTag("speak-0").requestFocus());key(KeyEvent.KEYCODE_DPAD_RIGHT);
        check("D-pad reaches Type / edit",a.getCurrentFocus() instanceof Button&&((Button)a.getCurrentFocus()).getText().toString().equals("Type / edit"));shot("11-reply");
        check("draft payload preserves action and mode",a.payload(null).getString("action").equals("send")&&a.payload(null).getString("mode").equals("queue"));
        String[] modeKeys={"auto","queue","steer"},modeLabels={"Send and continue","Queue follow-up","Update current task"};
        for(int i=0;i<modeKeys.length;i++){
            final String label=modeLabels[i];ui(()->byText(a.getWindow().getDecorView(),label).performClick());
            check("delivery choice preserves "+modeKeys[i]+" payload",a.payload(null).getString("mode").equals(modeKeys[i]));
        }
        ActivityMonitor unavailable=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(intent.getAction()))throw new ActivityNotFoundException("Synthetic absent recognizer");return null;}};
        addMonitor(unavailable);try{ui(()->a.speak(0));}finally{removeMonitor(unavailable);}
        check("missing recognizer offers Type / edit",screen().contains("Voice input is unavailable. Use Type / edit"));shot("17-speech-unavailable");
        ui(()->a.onActivityResult(ComposeActivity.VOICE,Activity.RESULT_OK,new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList("Keep the green outline.")))));
        check("transcript enters draft without submitting",a.fields.get(0).edit.getText().toString().contains("Keep the green outline.")&&a.frozen==null&&!a.sending);
        check("transcript requires explicit Send",screen().contains("Review the transcript, then select Send.")&&a.getCurrentFocus()==a.send);shot("18-transcript-review");
        key(KeyEvent.KEYCODE_BACK);active=null;
        final ComposeActivity restored=open(ComposeActivity.class,composeIntent("qa-reply",true));
        check("Back preserves the typed draft",restored.fields.get(0).edit.getText().toString().contains("darker background"));
        close();open(ComposeActivity.class,composeIntent("qa-inactive",false));check("inactive task hides steer mode",!screen().contains("Update current task"));shot("12-inactive-reply");close();
        JSONObject request=new JSONObject("{\"id\":\"q1\",\"kind\":\"user_input\",\"questions\":[{\"id\":\"choice\",\"question\":\"Choose the dashboard background\",\"required\":true,\"allowCustomAnswer\":true,\"options\":[{\"label\":\"Dark navy\",\"value\":\"navy\"},{\"label\":\"Charcoal\",\"value\":\"charcoal\"}]},{\"id\":\"notes\",\"question\":\"Describe any other changes\",\"required\":true,\"allowCustomAnswer\":true}]}");
        ComposeActivity q=open(ComposeActivity.class,composeIntent("qa-questions",true).putExtra("request",request.toString()));
        check("required questions block readiness until answered",!q.readyToSend());
        ui(()->{q.fields.get(0).options.get(0).performClick();q.fields.get(1).edit.setText("Keep the green outline.");});
        check("both required answers permit readiness",q.readyToSend());check("answers remain request-specific",q.payload(null).getString("requestId").equals("q1"));shot("13-questions-top");
        ui(()->q.form.findViewWithTag("speak-0").requestFocus());
        for(int i=0;i<12&&!String.valueOf(q.getCurrentFocus().getTag()).equals("speak-1");i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
        check("D-pad reveals the second question controls",String.valueOf(q.getCurrentFocus().getTag()).equals("speak-1")&&visible(q.getCurrentFocus()));shot("13-questions");close();
        JSONObject permission=new JSONObject("{\"id\":\"p1\",\"kind\":\"command\",\"prompt\":\"Allow the agent to run the fixture test command?\"}");
        ComposeActivity p=open(ComposeActivity.class,composeIntent("qa-permission",true).putExtra("request",permission.toString()));
        check("permission has dedicated decisions and desktop restriction",screen().contains("Allow once")&&screen().contains("Decline")&&screen().contains("permissions must be reviewed in T3 Code"));
        check("permission payload keeps explicit one-time decision",p.payload("accept").getString("action").equals("approve")&&p.payload("accept").getString("decision").equals("accept"));
        ui(()->byText(p.getWindow().getDecorView(),"Allow once").performClick());check("permission decision requires visible confirmation",dialogContains("Confirm permission decision"));shot("14-permission-confirm");key(KeyEvent.KEYCODE_BACK);close();
        String retry="11111111-1111-4111-8111-111111111111";
        JSONObject submission=new JSONObject().put("environmentId","e0").put("threadId","qa-retry").put("action","send").put("mode","queue").put("text","Fixture reply").put("clientRequestId",retry);
        getTargetContext().getSharedPreferences("drafts",0).edit().putString("e0|qa-retry|reply|submission",submission.toString()).commit();
        ComposeActivity f=open(ComposeActivity.class,composeIntent("qa-retry",true));
        check("uncertain submission retains its retry identity",f.frozen.getString("clientRequestId").equals(retry));
        check("frozen draft exposes retry and locks editor",screen().contains("Retry delivery")&&!f.fields.get(0).edit.isEnabled());shot("15-retry");close();
        for(String delivery:new String[]{"queued","steered"}){
            ComposeActivity accepted=open(ComposeActivity.class,composeIntent("qa-result-"+delivery,true));
            ui(()->accepted.showAccepted(delivery));
            String message=delivery.equals("queued")?"Follow-up queued after the current task.":"Update sent to the active task.";
            check(delivery+" acceptance confirmation is rendered",dialogContains(message));shot("19-"+delivery+"-confirmation");key(KeyEvent.KEYCODE_BACK);active=null;
        }
    }
    void conversation()throws Exception{
        ConversationActivity c=open(ConversationActivity.class,composeIntent("qa-conversation",false));
        ui(()->{c.resumed=false;c.handler.removeCallbacks(c.tick);});
        long until=SystemClock.uptimeMillis()+3000;while(c.busy&&SystemClock.uptimeMillis()<until)SystemClock.sleep(20);
        JSONObject data=new JSONObject("{\"title\":\"Review a long dashboard conversation title and its latest response\",\"status\":\"failed\",\"model\":\"Codex\",\"active\":false,\"hasMoreHistory\":false,\"historyCursor\":null,\"requests\":[{\"id\":\"conversation-question\",\"kind\":\"user_input\",\"resumable\":true,\"questions\":[{\"id\":\"choice\",\"question\":\"Keep the green focus ring?\",\"allowCustomAnswer\":true}]}],\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"position\":1,\"text\":\"Review the dashboard layout and connection states.\"},{\"id\":\"m2\",\"role\":\"assistant\",\"position\":2,\"text\":\"The fixture check failed. Review the latest response before replying. This is sample text only.\"}]}");
        ui(()->c.apply(data,false));
        check("conversation exposes visible Reply and Requests",visible(c.reply)&&visible(c.questions)&&screen().contains("Failed"));shot("20-conversation");
        ActivityMonitor monitor=addMonitor(ComposeActivity.class.getName(),null,false);
        ui(()->c.reply.performClick());Activity reply=monitor.waitForActivityWithTimeout(3000);removeMonitor(monitor);
        check("conversation Reply opens the scoped composer",reply instanceof ComposeActivity&&reply.getIntent().getStringExtra("threadId").equals("qa-conversation"));
        if(reply==null)throw new IllegalStateException("Reply composer did not open");active=reply;key(KeyEvent.KEYCODE_BACK);active=c;
        ui(()->{c.resumed=false;c.handler.removeCallbacks(c.tick);c.apply(data,false);});
        monitor=addMonitor(ComposeActivity.class.getName(),null,false);ui(()->c.questions.performClick());Activity answer=monitor.waitForActivityWithTimeout(3000);removeMonitor(monitor);
        check("conversation Requests opens its specific question",answer instanceof ComposeActivity&&((ComposeActivity)answer).request.optString("id").equals("conversation-question"));
        if(answer!=null){active=answer;close();}active=c;close();
    }
    void storageFailure()throws Exception{
        final ComposeActivity c=open(ComposeActivity.class,composeIntent("qa-write-failure",true));
        ui(()->c.fields.get(0).edit.setText("Previously persisted fixture draft."));
        File prefs=new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs");
        if(!prefs.setWritable(false,false))throw new IOException("Could not isolate a preview-only write failure");
        try{
            ui(()->c.fields.get(0).edit.setText("Uncommitted fixture edit."));
            check("write failure shows an amber save warning",screen().contains("Draft could not be saved"));
            check("failed write leaves edited value in memory",c.drafts.getString(c.key+"|field|reply","").contains("Uncommitted"));
            close();ComposeActivity reopened=open(ComposeActivity.class,composeIntent("qa-write-failure",true));
            check("reopen does not promote memory to a saved claim",screen().contains("Draft could not be saved")&&!screen().contains("Draft saved on this TV"));shot("21-failed-save-reopen");
            if(!prefs.setWritable(true,true))throw new IOException("Could not restore preview preference permissions");
            ui(()->reopened.noteSaved(reopened.drafts.edit().commit()));
            check("successful later commit restores saved status",screen().contains("Draft saved on this TV"));
        }finally{prefs.setWritable(true,true);close();}
    }
    void parserFailures()throws Exception{
        ui(()->{MonitorService.snapshot=null;MonitorService.lastSuccess=0;MonitorService.feedState=DashboardState.CONNECTING;});
        MainActivity a=open(MainActivity.class,new Intent());key(KeyEvent.KEYCODE_BACK);
        File config=new File(getTargetContext().getFilesDir(),"connection.json");
        try{
            try(FileWriter out=new FileWriter(config)){out.write("{\"token\":\"SYNTHETIC_PRIVATE_MARKER\",\"url\":");}
            getTargetContext().startForegroundService(new Intent(getTargetContext(),MonitorService.class));
            long until=SystemClock.uptimeMillis()+5000;while(MonitorService.feedState!=DashboardState.UNAVAILABLE&&SystemClock.uptimeMillis()<until)SystemClock.sleep(20);
            ui(a::render);
            check("real malformed-config poll reports an initial error",MonitorService.feedState==DashboardState.UNAVAILABLE&&screen().contains("No update received")&&screen().contains("Bridge unavailable"));
            check("first error claims neither cached nor live data",!screen().contains("Showing cached data")&&!screen().contains("● Live"));
            check("config parser input stays out of diagnostics",!screen().contains("SYNTHETIC_PRIVATE_MARKER")&&!MonitorService.feedDetail.contains("SYNTHETIC_PRIVATE_MARKER"));shot("22-first-error");
        }finally{getTargetContext().stopService(new Intent(getTargetContext(),MonitorService.class));config.delete();}
        render(a,feed(false),DashboardState.LIVE);
        check("first successful fixture response clears initial error",screen().contains("● Live")&&!screen().contains("Bridge unavailable"));
        Exception parser;
        try{new JSONObject("{\"response\":\"SYNTHETIC_RESPONSE_MARKER\",broken");throw new AssertionError("Fixture JSON should fail");}catch(JSONException e){parser=e;}
        final Exception failure=parser;
        ui(()->{MonitorService.lastSuccess=System.currentTimeMillis()-52000;MonitorService.feedFailed(failure);a.render();});
        check("response parser input stays out of cached diagnostics",screen().contains("Showing cached data")&&!screen().contains("SYNTHETIC_RESPONSE_MARKER")&&!MonitorService.feedDetail.contains("SYNTHETIC_RESPONSE_MARKER"));shot("23-safe-cached-error");close();
    }
    public void onStart(){
        if(!getTargetContext().getPackageName().equals("com.wilde.t3tv.preview")||new File(getTargetContext().getFilesDir(),"connection.json").exists()){
            Bundle rejection=new Bundle();rejection.putString("stream","Refusing fixtures outside an unconfigured isolated preview.");finish(Activity.RESULT_CANCELED,rejection);return;
        }
        try{
            SharedPreferences prefs=getTargetContext().getSharedPreferences("drafts",0);SharedPreferences.Editor edit=prefs.edit();for(String key:prefs.getAll().keySet())if(key.startsWith("e0|qa-"))edit.remove(key);edit.commit();
            dashboard();conversation();composer();storageFailure();parserFailures();
        }catch(Throwable e){failures++;log.add("ERROR "+e);for(StackTraceElement s:e.getStackTrace())log.add(s.toString());}
        finally{try{close();File dir=new File(getTargetContext().getFilesDir(),"qa-"+profile);dir.mkdirs();try(PrintWriter out=new PrintWriter(new File(dir,"results.txt"))){out.println("Candidate: "+head);out.println("Native Android TV fixture QA: "+profile);for(String line:log)out.println(line);out.println("Checks: "+checks+", failures/errors: "+failures);out.println("Synthetic preview fixtures only. Malformed connection file removed; no Send/Confirm or speech recording.");}}catch(Exception e){failures++;}Bundle result=new Bundle();result.putString("stream",checks+" checks; "+failures+" failures/errors");finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    }
}
