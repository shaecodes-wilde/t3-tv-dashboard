package com.wilde.t3tv;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.*;
import android.speech.RecognizerIntent;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class ComposeActivity extends Activity {
    String environmentId,threadId,key,mode;JSONObject request,frozen;
    SharedPreferences drafts;LinearLayout form;TextView status,draftState,ready;Button send;
    final ArrayList<Field> fields=new ArrayList<>();final ArrayList<View> controls=new ArrayList<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();final Handler handler=new Handler(Looper.getMainLooper());
    boolean sending=false,initializing=true,retired=false;int voiceIndex=0;
    static final int VOICE=411;
    static class Field {String id;JSONObject question;EditText edit;ArrayList<CheckBox> options=new ArrayList<>();ArrayList<String> values=new ArrayList<>();}

    @Override public void onCreate(Bundle state){super.onCreate(state);
        environmentId=getIntent().getStringExtra("environmentId");threadId=getIntent().getStringExtra("threadId");
        try{if(getIntent().hasExtra("request"))request=new JSONObject(getIntent().getStringExtra("request"));}catch(JSONException ignored){}
        key=environmentId+"|"+threadId+"|"+(request==null?"reply":request.optString("id"));drafts=getSharedPreferences("drafts",0);
        mode=drafts.getString(key+"|mode",getIntent().getBooleanExtra("active",false)?"queue":"auto");
        voiceIndex=state!=null?state.getInt("voiceIndex",0):drafts.getInt(key+"|voiceIndex",0);
        try{String saved=drafts.getString(key+"|submission",null);if(saved!=null)frozen=new JSONObject(saved);}catch(JSONException ignored){}
        LinearLayout root=TvUi.root(this);
        TextView heading=TvUi.text(this,request==null?"Reply to task":request.optString("kind").equals("user_input")?"Answer agent questions":"Review permission",25,TvUi.WHITE);root.addView(heading);
        TextView context=TvUi.text(this,getIntent().getStringExtra("environment")+"  /  "+getIntent().getStringExtra("title"),13,TvUi.MUTED);context.setMaxLines(2);context.setPadding(0,TvUi.dp(this,5),0,TvUi.dp(this,10));root.addView(context);
        status=TvUi.text(this,"Review your message before sending.",13,TvUi.MUTED);root.addView(status);
        ScrollView scroll=new ScrollView(this);form=TvUi.column(this);form.setPadding(0,TvUi.dp(this,10),0,TvUi.dp(this,10));scroll.addView(form);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(request==null){addMode();addField(new JSONObject(),"reply","Your reply",true);}
        else if(request.optString("kind").equals("user_input")){
            JSONArray questions=request.optJSONArray("questions");if(questions!=null)for(int i=0;i<questions.length();i++){JSONObject q=questions.optJSONObject(i);addField(q,q.optString("id"),q.optString("question"),q.optBoolean("allowCustomAnswer",true));}
        }else{addApproval();}
        LinearLayout bottom=TvUi.row(this);
        send=TvUi.button(this,"Send",()->submit(null));if(request==null||request.optString("kind").equals("user_input"))TvUi.addButton(bottom,send,125);
        else {TvUi.addButton(bottom,send,160);send.setVisibility(frozen==null?View.GONE:View.VISIBLE);}
        TvUi.addButton(bottom,TvUi.button(this,"Back · keep draft",this::finish),175);
        TvUi.addButton(bottom,TvUi.button(this,"Discard draft",this::discard),155);
        Space space=new Space(this);bottom.addView(space,new LinearLayout.LayoutParams(0,1,1));
        ready=TvUi.text(this,"Ready to send",13,TvUi.GREEN);ready.setVisibility(View.GONE);bottom.addView(ready);root.addView(bottom);
        LinearLayout footer=TvUi.row(this);footer.setPadding(0,TvUi.dp(this,10),0,0);
        draftState=TvUi.text(this,"",12,TvUi.MUTED);footer.addView(draftState,new LinearLayout.LayoutParams(0,-2,1));
        footer.addView(TvUi.text(this,"D-pad Move focus     OK Select     Back keep draft",11,TvUi.MUTED));root.addView(footer);
        // A failed commit can leave newer values in memory; confirm them on disk before claiming a save.
        initializing=false;setFrozen();noteSaved(drafts.edit().commit());updateReady();
        if(!fields.isEmpty()){View focus=form.findViewWithTag("speak-0");if(focus!=null)focus.requestFocus();}
    }

    void addMode(){
        LinearLayout modes=TvUi.row(this);
        String[] keys={"auto","queue","steer"};String[] labels={"Send and continue","Queue follow-up","Update current task"};
        ArrayList<RadioButton> radios=new ArrayList<>();
        for(int i=0;i<keys.length;i++){
            final String value=keys[i];RadioButton r=new RadioButton(this);r.setText(labels[i]);r.setTextColor(TvUi.WHITE);r.setTextSize(13);r.setChecked(mode.equals(value));TvUi.focus(r);
            if(value.equals("steer")&&!getIntent().getBooleanExtra("active",false))r.setVisibility(View.GONE);
            radios.add(r);controls.add(r);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,TvUi.dp(this,42),1);lp.rightMargin=TvUi.dp(this,8);modes.addView(r,lp);
            r.setOnClickListener(v->{mode=value;for(RadioButton other:radios)other.setChecked(other==r);noteSaved(drafts.edit().putString(key+"|mode",mode).commit());});
        }
        form.addView(modes);
    }

    void addField(JSONObject q,String id,String label,boolean custom){
        Field f=new Field();f.id=id;f.question=q;final int index=fields.size();fields.add(f);
        TextView caption=TvUi.text(this,label,17,TvUi.WHITE);caption.setPadding(0,TvUi.dp(this,12),0,TvUi.dp(this,6));form.addView(caption);
        JSONObject saved=new JSONObject();try{saved=new JSONObject(drafts.getString(key+"|field|"+id,"{}"));}catch(JSONException ignored){}
        JSONArray selected=saved.optJSONArray("selected"),options=q.optJSONArray("options");
        if(options!=null)for(int i=0;i<options.length();i++){
            JSONObject option=options.optJSONObject(i);String value=option.optString("value",option.optString("label"));
            CheckBox box=new CheckBox(this);box.setText(option.optString("label")+(option.optString("description").isEmpty()?"":" — "+option.optString("description")));box.setTextColor(TvUi.WHITE);box.setTextSize(14);TvUi.focus(box);
            if(selected!=null)for(int j=0;j<selected.length();j++)if(value.equals(selected.optString(j)))box.setChecked(true);
            f.options.add(box);f.values.add(value);controls.add(box);form.addView(box,new LinearLayout.LayoutParams(-1,-2));
            box.setOnClickListener(v->{if(box.isChecked()){if(!q.optBoolean("multiSelect",false))for(CheckBox other:f.options)if(other!=box)other.setChecked(false);if(f.edit!=null)f.edit.setText("");}save(f);});
        }
        if(custom){
            f.edit=new EditText(this);f.edit.setTextSize(18);f.edit.setTextColor(TvUi.WHITE);f.edit.setHintTextColor(TvUi.MUTED);f.edit.setHint(options!=null&&options.length()>0?"Or give your own answer…":request==null?"Type or speak your reply…":"Type your answer…");
            f.edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            f.edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16000)});f.edit.setMinLines(request==null?4:2);f.edit.setMaxLines(7);f.edit.setGravity(Gravity.TOP);f.edit.setPadding(TvUi.dp(this,14),TvUi.dp(this,10),TvUi.dp(this,14),TvUi.dp(this,10));
            f.edit.setText(saved.optString("text"));TvUi.focus(f.edit);f.edit.setFocusableInTouchMode(true);f.edit.setShowSoftInputOnFocus(false);form.addView(f.edit,new LinearLayout.LayoutParams(-1,-2));controls.add(f.edit);
            f.edit.setOnClickListener(v->keyboard(f));
            f.edit.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int st,int before,int count){if(s.length()>0)for(CheckBox box:f.options)box.setChecked(false);save(f);}public void afterTextChanged(Editable e){}});
            LinearLayout buttons=TvUi.row(this);buttons.setPadding(0,TvUi.dp(this,8),0,0);
            Button speak=TvUi.button(this,"Speak",()->speak(index));speak.setTag("speak-"+index);controls.add(speak);TvUi.addButton(buttons,speak,110);
            Button keyboard=TvUi.button(this,"Type / edit",()->keyboard(f));controls.add(keyboard);TvUi.addButton(buttons,keyboard,150);
            Button clear=TvUi.button(this,"Clear text",()->f.edit.setText(""));controls.add(clear);TvUi.addButton(buttons,clear,120);form.addView(buttons);
        }
    }
    void keyboard(Field f){if(f.edit==null)return;f.edit.requestFocus();f.edit.setSelection(f.edit.length());getSystemService(InputMethodManager.class).showSoftInput(f.edit,InputMethodManager.SHOW_FORCED);}
    void save(Field f){if(initializing||retired)return;try{JSONArray values=new JSONArray();for(int i=0;i<f.options.size();i++)if(f.options.get(i).isChecked())values.put(f.values.get(i));JSONObject value=new JSONObject().put("selected",values).put("text",f.edit==null?"":f.edit.getText().toString());noteSaved(drafts.edit().putString(key+"|field|"+f.id,value.toString()).commit());}catch(JSONException ignored){}updateReady();}
    boolean hasDraft(){for(String k:drafts.getAll().keySet())if(k.startsWith(key+"|")&&!k.equals(key+"|voiceIndex"))return true;return false;}
    void noteSaved(boolean committed){
        if(committed){boolean saved=hasDraft();draftState.setText(saved?"● Draft saved on this TV":"No saved draft yet");draftState.setTextColor(saved?TvUi.GREEN:TvUi.MUTED);}
        else{draftState.setText("Draft could not be saved on this TV.");draftState.setTextColor(TvUi.AMBER);}
    }
    boolean readyToSend(){
        if(sending||frozen!=null||retired)return false;
        if(request==null)return fields.get(0).edit!=null&&!fields.get(0).edit.getText().toString().trim().isEmpty();
        if(!request.optString("kind").equals("user_input"))return false;
        for(Field f:fields){
            if(!f.question.optBoolean("required",true))continue;
            boolean answered=f.edit!=null&&!f.edit.getText().toString().trim().isEmpty();
            if(!answered)for(CheckBox box:f.options)if(box.isChecked()){answered=true;break;}
            if(!answered)return false;
        }
        return true;
    }
    void updateReady(){if(ready!=null)ready.setVisibility(readyToSend()?View.VISIBLE:View.GONE);}

    void speak(int index){
        if(frozen!=null||index<0||index>=fields.size()||fields.get(index).edit==null)return;
        voiceIndex=index;drafts.edit().putInt(key+"|voiceIndex",index).apply();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(fields.get(index).edit.getWindowToken(),0);
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-CA").putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak your reply. You will review it before sending.").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        // Prefer the TV's recognizer, which knows how to route its Bluetooth remote microphone.
        for(ResolveInfo info:getPackageManager().queryIntentActivities(intent,0))if(info.activityInfo.packageName.equals("com.google.android.katniss")){intent.setPackage(info.activityInfo.packageName);break;}
        try{startActivityForResult(intent,VOICE);status.setText("Listening through the system voice screen…");status.setTextColor(TvUi.MUTED);}
        catch(ActivityNotFoundException|SecurityException e){status.setText("Voice input is unavailable. Use Type / edit and its microphone.");status.setTextColor(TvUi.AMBER);}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);
        if(code!=VOICE)return;
        ArrayList<String> words=data==null?null:data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if(result==RESULT_OK&&words!=null&&!words.isEmpty()&&voiceIndex<fields.size()&&frozen==null){
            EditText edit=fields.get(voiceIndex).edit;if(edit!=null){String before=edit.getText().toString();String addition=words.get(0).trim();edit.setText(before+(before.isEmpty()||before.endsWith(" ")?"":" ")+addition);edit.setSelection(edit.length());}
            status.setText("Review the transcript, then select Send.");status.setTextColor(TvUi.MUTED);send.requestFocus();
        }else {status.setText("No transcript received. Your draft is unchanged; choose Speak to try again.");status.setTextColor(TvUi.MUTED);}
    }
    @Override public boolean onKeyUp(int code,KeyEvent event){if((code==KeyEvent.KEYCODE_SEARCH||code==KeyEvent.KEYCODE_VOICE_ASSIST)&&!fields.isEmpty()){speak(Math.min(voiceIndex,fields.size()-1));return true;}return super.onKeyUp(code,event);}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("voiceIndex",voiceIndex);super.onSaveInstanceState(state);}
    @Override protected void onPause(){for(Field f:fields)save(f);super.onPause();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}

    void addApproval(){
        form.addView(TvUi.text(this,request.optString("prompt"),18,TvUi.WHITE));
        JSONArray options=request.optJSONArray("options");ArrayList<String> decisions=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        if(options!=null&&options.length()>0){for(int i=0;i<options.length();i++){JSONObject o=options.optJSONObject(i);String d=o.optString("decision");if(Arrays.asList("accept","decline","cancel").contains(d)){decisions.add(d);labels.add(o.optString("label")+(o.optString("warning").isEmpty()?"":" — "+o.optString("warning")));}}}
        else if(!request.optString("kind").equals("user_input")&&Arrays.asList("command","file-read","file-change","mcp-elicitation","permission").contains(request.optString("kind"))){decisions.add("accept");labels.add("Allow once");decisions.add("decline");labels.add("Decline");}
        for(int i=0;i<decisions.size();i++){final String decision=decisions.get(i),label=labels.get(i);Button b=TvUi.button(this,label,()->new AlertDialog.Builder(this).setTitle("Confirm permission decision").setMessage(request.optString("prompt")+"\n\n"+label+"?").setNegativeButton("Back",null).setPositiveButton("Confirm",(d,w)->submit(decision)).show());controls.add(b);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,TvUi.dp(this,48));lp.topMargin=TvUi.dp(this,12);form.addView(b,lp);}
        form.addView(TvUi.text(this,"Session-wide or permanent permissions must be reviewed in T3 Code on your computer.",13,TvUi.MUTED));
    }

    JSONObject payload(String decision) throws Exception{
        JSONObject body=new JSONObject().put("environmentId",environmentId).put("threadId",threadId).put("clientRequestId",UUID.randomUUID().toString());
        if(request==null){String text=fields.get(0).edit.getText().toString().trim();if(text.isEmpty())throw new Exception("Speak or type a reply first.");return body.put("action","send").put("text",text).put("mode",mode);}
        body.put("requestId",request.getString("id"));
        if(!request.optString("kind").equals("user_input"))return body.put("action","approve").put("decision",decision);
        JSONObject answers=new JSONObject();
        for(Field f:fields){JSONArray values=new JSONArray();String text=f.edit==null?"":f.edit.getText().toString().trim();if(!text.isEmpty())values.put(text);else for(int i=0;i<f.options.size();i++)if(f.options.get(i).isChecked())values.put(f.values.get(i));if(f.question.optBoolean("required",true)&&values.length()==0)throw new Exception("Answer every required question before sending.");answers.put(f.id,values);}
        return body.put("action","answer").put("answers",answers);
    }
    void setFrozen(){boolean lock=frozen!=null;for(View v:controls)v.setEnabled(!lock);send.setEnabled(!sending);send.setText(lock?"Retry delivery":"Send");if(lock){send.setVisibility(View.VISIBLE);status.setText("A submission is saved. Retry to confirm delivery without sending a duplicate.");}updateReady();}
    void submit(String decision){
        if(sending)return;
        try{if(frozen==null){frozen=payload(decision);if(!drafts.edit().putString(key+"|submission",frozen.toString()).commit())throw new Exception("Could not save this reply. Try again.");}}
        catch(Exception e){frozen=null;status.setText(e.getMessage());updateReady();return;}
        sending=true;setFrozen();status.setText("Sending to "+getIntent().getStringExtra("environment")+"…");status.setTextColor(TvUi.MUTED);send.setText("Sending…");
        final JSONObject body=frozen;
        worker.execute(()->{
            try{JSONObject result=FeedClient.request(this,"/v1/action",body);if(!result.optBoolean("accepted"))throw new Exception("Delivery not confirmed. Retry to check safely.");clearDraft();
                handler.post(()->showAccepted(result.optString("delivery")));
            }catch(Exception e){handler.post(()->{sending=false;if(isDestroyed())return;setFrozen();status.setText(e.getMessage()+"\nDraft saved. Retry delivery uses the same request ID.");status.setTextColor(TvUi.AMBER);});}
        });
    }
    void showAccepted(String delivery){
        sending=false;if(isDestroyed())return;
        String message=delivery.equals("queued")?"Follow-up queued after the current task.":delivery.equals("steered")?"Update sent to the active task.":delivery.equals("started")?"T3 has started the next turn for your reply.":request==null?"Your reply is saved in the conversation. Return to see the current task status.":"Your answer was accepted. Return to the conversation for the next update.";
        new AlertDialog.Builder(this).setTitle("Accepted by T3").setMessage(message).setPositiveButton("View conversation",(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
    void clearDraft(){retired=true;SharedPreferences.Editor edit=drafts.edit();for(String k:drafts.getAll().keySet())if(k.startsWith(key+"|"))edit.remove(k);edit.commit();}
    void discard(){if(sending)return;new AlertDialog.Builder(this).setTitle("Discard this draft?").setMessage(frozen==null?"The saved text will be removed.":"Delivery may already have succeeded. Check the conversation before sending a new reply. Discarding does not cancel a message T3 accepted.").setNegativeButton("Keep",null).setPositiveButton("Discard",(d,w)->{clearDraft();finish();}).show();}
}
