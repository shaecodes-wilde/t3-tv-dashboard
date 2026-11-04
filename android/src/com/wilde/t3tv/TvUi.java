package com.wilde.t3tv;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class TvUi {
    static final int BG=0xff0b111b, PANEL=0xff151e2b, MUTED=0xff9baabd, WHITE=0xfff2f6fb, GREEN=0xffb7f876, AMBER=0xffffce7a;
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static void fullscreen(Activity a){a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    static TextView text(Context c,String s,int size,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static GradientDrawable background(Context c,boolean focus){GradientDrawable d=new GradientDrawable();d.setColor(focus?0xff263b38:PANEL);d.setCornerRadius(dp(c,8));d.setStroke(dp(c,focus?2:1),focus?GREEN:0xff263547);return d;}
    static void focus(View v){v.setFocusable(true);v.setBackground(background(v.getContext(),false));v.setOnFocusChangeListener((w,f)->w.setBackground(background(w.getContext(),f)));}
    static Button button(Context c,String label,Runnable action){Button b=new Button(c);b.setText(label);b.setTextSize(13);b.setTextColor(WHITE);b.setAllCaps(false);b.setPadding(dp(c,12),0,dp(c,12),0);b.setMinimumHeight(0);b.setMinHeight(0);focus(b);b.setOnClickListener(v->action.run());return b;}
    static void addButton(LinearLayout row,Button b,int width){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(row.getContext(),width),dp(row.getContext(),40));lp.rightMargin=dp(row.getContext(),10);row.addView(b,lp);}
    static LinearLayout root(Activity a){fullscreen(a);LinearLayout root=column(a);root.setBackgroundColor(BG);root.setPadding(dp(a,36),dp(a,22),dp(a,36),dp(a,18));a.setContentView(root);return root;}
}
