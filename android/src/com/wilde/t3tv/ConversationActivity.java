package com.wilde.t3tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class ConversationActivity extends Activity {
    String environmentId,threadId,environment,title,cursor=null,signature="";
    JSONObject current;
    final LinkedHashMap<String,JSONObject> messages=new LinkedHashMap<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Handler handler=new Handler(Looper.getMainLooper());
    TextView status,heading;LinearLayout list;ScrollView scroll;Button older,questions,reply;
    boolean busy=false,resumed=false,first=true;
    final Runnable tick=new Runnable(){public void run(){if(resumed){load(false);handler.postDelayed(this,6000);}}};

    @Override public void onCreate(Bundle state){super.onCreate(state);
        environmentId=getIntent().getStringExtra("environmentId");threadId=getIntent().getStringExtra("threadId");environment=getIntent().getStringExtra("environment");title=getIntent().getStringExtra("title");
        LinearLayout root=TvUi.root(this),bar=TvUi.row(this);
        TvUi.addButton(bar,TvUi.button(this,"‹ Dashboard",this::finish),125);
        bar.addView(TvUi.text(this,environment+"  /  CONVERSATION",13,TvUi.GREEN));root.addView(bar);
        heading=TvUi.text(this,title,24,TvUi.WHITE);heading.setMaxLines(2);heading.setPadding(0,TvUi.dp(this,12),0,TvUi.dp(this,7));root.addView(heading);
        LinearLayout actions=TvUi.row(this);
        reply=TvUi.button(this,"Reply",()->compose(null));reply.setEnabled(false);TvUi.addButton(actions,reply,110);
        questions=TvUi.button(this,"Questions",this::requests);questions.setEnabled(false);TvUi.addButton(actions,questions,160);
        TvUi.addButton(actions,TvUi.button(this,"Refresh",()->load(false)),100);
        older=TvUi.button(this,"Older messages",()->load(true));older.setEnabled(false);TvUi.addButton(actions,older,155);root.addView(actions);
        status=TvUi.text(this,"Loading conversation…",12,TvUi.MUTED);status.setPadding(0,TvUi.dp(this,10),0,TvUi.dp(this,8));root.addView(status);
        scroll=new ScrollView(this);list=TvUi.column(this);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=TvUi.text(this,"↑ ↓ Scroll messages     OK Select     Back Dashboard",11,TvUi.MUTED);hint.setPadding(0,TvUi.dp(this,8),0,0);root.addView(hint);
    }
    @Override protected void onResume(){super.onResume();resumed=true;handler.post(tick);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}
    JSONObject target() throws JSONException{return new JSONObject().put("environmentId",environmentId).put("threadId",threadId);}
    void load(boolean history){
        if(busy||(history&&cursor==null))return;busy=true;
        if(history)status.setText("Loading older messages…");
        final String next=cursor;
        worker.execute(()->{
            try {JSONObject body=target();if(history)body.put("cursor",next);JSONObject data=FeedClient.request(this,history?"/v1/history":"/v1/thread",body);
                handler.post(()->{busy=false;if(isDestroyed())return;try{apply(data,history);}catch(JSONException e){status.setText("Unexpected conversation data. Try Refresh.");}});
            }catch(Exception e){handler.post(()->{busy=false;if(!isDestroyed()){status.setText(e.getMessage()+" · Refresh to retry");status.setTextColor(TvUi.AMBER);}});}
        });
    }
    void apply(JSONObject data,boolean history) throws JSONException{
        if(!history){current=data;heading.setText(data.optString("title",title));reply.setEnabled(true);JSONArray requests=data.getJSONArray("requests");questions.setEnabled(requests.length()>0);questions.setText(requests.length()>0?"Requests ("+requests.length()+")":"No requests");}
        status.setText(history?"Older messages loaded":DashboardState.statusLabel(data.optString("status","idle"))+"  ·  "+data.optString("model")+"  ·  Live");status.setTextColor(TvUi.GREEN);
        if(history||first){cursor=data.isNull("historyCursor")?null:data.optString("historyCursor",null);older.setEnabled(data.optBoolean("hasMoreHistory")&&cursor!=null);}
        JSONArray rows=data.getJSONArray("messages");for(int i=0;i<rows.length();i++){JSONObject m=rows.getJSONObject(i);messages.put(m.getString("id"),m);}
        ArrayList<JSONObject> ordered=new ArrayList<>(messages.values());ordered.sort(Comparator.comparingLong(m->m.optLong("position")));
        String nextSignature=ordered.toString();if(nextSignature.equals(signature))return;signature=nextSignature;
        int y=scroll.getScrollY(),oldHeight=list.getHeight();boolean bottom=oldHeight-y-scroll.getHeight()<TvUi.dp(this,80);
        String focus=getCurrentFocus()!=null?String.valueOf(getCurrentFocus().getTag()):"";
        list.removeAllViews();View restore=null;
        for(JSONObject m:ordered){
            LinearLayout card=TvUi.column(this);card.setPadding(TvUi.dp(this,15),TvUi.dp(this,12),TvUi.dp(this,15),TvUi.dp(this,14));TvUi.focus(card);card.setTag(m.optString("id"));
            card.addView(TvUi.text(this,m.optString("role").equals("user")?"YOU":"AGENT"+(m.optBoolean("streaming")?" · writing…":""),11,m.optString("role").equals("user")?TvUi.GREEN:TvUi.MUTED));
            TextView content=TvUi.text(this,m.optString("text"),16,TvUi.WHITE);content.setPadding(0,TvUi.dp(this,6),0,0);card.addView(content);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=TvUi.dp(this,10);list.addView(card,lp);if(focus.equals(card.getTag()))restore=card;
        }
        if(ordered.isEmpty())list.addView(TvUi.text(this,"No messages yet. Send a reply to begin.",18,TvUi.MUTED));
        if(restore!=null)restore.requestFocus();
        final boolean jump=first||bottom;if(first)reply.requestFocus();first=false;
        scroll.post(()->{if(history)scroll.scrollTo(0,y+Math.max(0,list.getHeight()-oldHeight));else if(jump)scroll.scrollTo(0,list.getHeight());else scroll.scrollTo(0,y);});
    }
    void compose(JSONObject request){
        if(current==null)return;
        Intent intent=new Intent(this,ComposeActivity.class).putExtra("environmentId",environmentId).putExtra("threadId",threadId).putExtra("environment",environment).putExtra("title",heading.getText().toString()).putExtra("active",current.optBoolean("active"));
        if(request!=null)intent.putExtra("request",request.toString());startActivity(intent);
    }
    void requests(){
        if(current==null)return;JSONArray req=current.optJSONArray("requests");if(req==null||req.length()==0)return;
        if(req.length()==1){openRequest(req.optJSONObject(0));return;}
        String[] labels=new String[req.length()];for(int i=0;i<labels.length;i++){JSONObject r=req.optJSONObject(i);labels[i]=r.optString("kind").equals("user_input")?"Answer agent questions":"Review permission request";}
        new AlertDialog.Builder(this).setTitle("Waiting for you").setItems(labels,(d,i)->openRequest(req.optJSONObject(i))).show();
    }
    void openRequest(JSONObject request){
        if(!request.optBoolean("resumable")){new AlertDialog.Builder(this).setMessage("This request can no longer be resumed. Review it in T3 Code.").setPositiveButton("OK",null).show();return;}
        compose(request);
    }
}
